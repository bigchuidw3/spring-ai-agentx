package com.agentx.ai.core.agent.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.agentx.ai.core.exception.AgentErrorCode;
import com.agentx.ai.core.exception.AgentException;
import com.agentx.ai.core.interrupt.PauseStateStore;
import com.agentx.ai.core.interrupt.SafePoint;
import com.agentx.ai.core.interrupt.PauseReason;
import com.agentx.ai.core.model.AgentResult;
import com.agentx.ai.core.model.AgentStreamEvent;
import com.agentx.ai.core.model.PendingToolCall;
import com.agentx.ai.core.advisors.PauseAdvisor;
import com.agentx.ai.core.advisors.RequestLoggingAdvisor;
import com.agentx.ai.core.model.PauseState;
import com.agentx.ai.core.prompt.PromptConstants;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.model.StageOutputProvider;
import com.agentx.ai.core.model.ThinkingMode;
import com.agentx.ai.core.stage.AgentExecutionContext;
import com.agentx.ai.core.stage.StageOutputManager;
import com.agentx.ai.core.stage.ThinkTagParser;
import com.agentx.ai.core.timeline.TimelineCollector;
import com.agentx.ai.core.timeline.TimelineSerializer;
import com.agentx.ai.core.utils.JsonRepairUtil;

import com.agentx.ai.core.context.ContextCompactor;
import com.agentx.ai.core.memory.semantic.SemanticMemoryManager;
import com.agentx.ai.core.tools.toolsearch.DeferredToolRegistry;
import com.agentx.ai.core.memory.store.MemoryStore;
import com.agentx.ai.core.memory.util.MemoryInjector;
import com.agentx.ai.core.memory.util.MemoryPersistor;
import com.agentx.ai.core.trace.TraceManager;
import com.agentx.ai.core.trace.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks.EmitResult;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 循环执行器。
 * <p>
 * 负责执行 ReAct（Reasoning + Acting）循环，通过多轮迭代自动处理 LLM 响应和工具调用。
 * 每轮执行时调用 LLM 获取响应，检测是否有工具调用：如果有工具调用则执行后继续下一轮，
 * 如果没有则返回最终答案，直到达到最大轮次限制。
 * <p>
 * ChatClient 必须配置 internalToolExecutionEnabled(false)，框架才能完全控制工具调用。
 * 配置 AgentTaskManager 时，同一会话 ID 的并发请求会被拒绝，防止资源竞争。
 *
 * @author bigchui
 *
 */
public class AgentLoopExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopExecutor.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 重试间隔（毫秒），内置默认值
     */
    private final int maxRounds;
    private final Map<String, ToolCallback> toolMap;
    private final String askUserToolName;
    private final AgentTaskManager taskManager;
    private final MemoryInjector memoryInjector;
    private final MemoryPersistor memoryPersistor;
    private final LoopMessageBuilder messageBuilder;
    private final StageOutputManager stageManager;
    private final ThinkingMode thinkingMode;
    private final ThinkingModeProcessor thinkingModeProcessor;
    private final ToolCallExecutor toolCallExecutor;
    private final LlmInvoker llmInvoker;
    private final ContextCompactor contextCompactor;
    private final TraceStore traceStore;
    private final boolean enableSession;
    private final boolean enableTrace;
    /**
     * 中断状态持久化（可选）。非空时 interrupt 触发的 PauseState 会自动写入
     */
    private final PauseStateStore stateStore;

    /**
     * 中断上下文 — 每次执行（stream/call）独立创建，跟踪当前轮阶段以支持用户主动中断。
     * 通过实例字段持有，使 scheduleRound / finishRound / processChunk 等内部方法可访问。
     */
    private InterruptContext interruptContext;

    private AgentLoopExecutor(Builder builder) {
        this.maxRounds = builder.maxRounds;
        this.askUserToolName = builder.askUserToolName;
        this.taskManager = builder.taskManager;
        this.thinkingMode = builder.thinkingMode;
        this.thinkingModeProcessor = new ThinkingModeProcessor(builder.thinkingMode);
        this.contextCompactor = builder.contextCompactor;
        this.traceStore = builder.traceStore;
        this.enableSession = builder.enableSession;
        this.enableTrace = builder.enableTrace;
        this.stateStore = builder.stateStore;

        DeferredToolRegistry.Session deferredToolSession = builder.deferredToolRegistry != null
                ? builder.deferredToolRegistry.createSession()
                : null;
        List<Advisor> advisors = builder.advisors != null ? List.copyOf(builder.advisors) : List.of();
        List<ToolCallback> alwaysLoadTools = builder.tools != null ? List.copyOf(builder.tools) : List.of();
        this.stageManager = builder.stageOutputProviders != null && !builder.stageOutputProviders.isEmpty()
                ? new StageOutputManager(builder.stageOutputProviders)
                : StageOutputManager.EMPTY;

        // 构建 tool lookup Map（包含所有工具：alwaysLoad + deferred）
        Map<String, ToolCallback> map = new HashMap<>();
        if (builder.tools != null) {
            for (ToolCallback t : builder.tools) {
                map.put(t.getToolDefinition().name(), t);
            }
        }
        if (builder.deferredToolRegistry != null) {
            for (Map.Entry<String, ToolCallback> entry : builder.deferredToolRegistry.getAllDeferredTools().entrySet()) {
                map.put(entry.getKey(), entry.getValue());
            }
            ToolCallback searchCallback = deferredToolSession.getToolSearchCallback();
            map.put(searchCallback.getToolDefinition().name(), searchCallback);
        }
        this.toolMap = map;

        // LLM 调用器
        this.llmInvoker = new LlmInvoker(builder.chatClient, builder.chatModel,
                builder.maxRetries, advisors, alwaysLoadTools,
                builder.deferredToolRegistry, deferredToolSession);

        // 记忆注入器（对话开始时加载）
        this.memoryInjector = new MemoryInjector(
                builder.memoryStore, builder.semanticMemoryManager, builder.enableProfileMemory);

        // 记忆持久化器
        this.memoryPersistor = new MemoryPersistor(
                builder.memoryStore, builder.chatModel,
                builder.semanticMemoryManager, builder.enableProfileMemory);

        // 消息构建器
        boolean hasTodoWrite = map.containsKey("TodoWrite");
        this.messageBuilder = new LoopMessageBuilder(
                builder.instructions, builder.chatMemory,
                memoryInjector, builder.thinkingMode, builder.deferredToolRegistry,
                hasTodoWrite, builder.enableSession);

        // 工具调用执行器
        this.toolCallExecutor = new ToolCallExecutor(toolMap, new ObjectMapper(),
                builder.askUserToolName, stageManager);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private ChatClient chatClient;
        private int maxRounds = 100;
        private List<ToolCallback> tools;
        private AgentTaskManager taskManager;
        private ChatMemory chatMemory;
        private String instructions;
        private MemoryStore memoryStore;
        private ChatModel chatModel;
        private SemanticMemoryManager semanticMemoryManager;
        private boolean enableProfileMemory = true;
        private boolean enableSession = true;
        private boolean enableTrace = true;
        private String askUserToolName;
        private List<StageOutputProvider> stageOutputProviders;
        private ThinkingMode thinkingMode = ThinkingMode.DISABLED;
        private int maxRetries = 3;
        private ContextCompactor contextCompactor;
        private DeferredToolRegistry deferredToolRegistry;
        private List<Advisor> advisors;
        private TraceStore traceStore;
        private PauseStateStore stateStore;

        public Builder chatClient(ChatClient v) {
            this.chatClient = v;
            return this;
        }

        public Builder maxRounds(int v) {
            this.maxRounds = v;
            return this;
        }

        public Builder tools(List<ToolCallback> v) {
            this.tools = v;
            return this;
        }

        public Builder taskManager(AgentTaskManager v) {
            this.taskManager = v;
            return this;
        }

        public Builder chatMemory(ChatMemory v) {
            this.chatMemory = v;
            return this;
        }

        public Builder instructions(String v) {
            this.instructions = v;
            return this;
        }

        public Builder memoryStore(MemoryStore v) {
            this.memoryStore = v;
            return this;
        }

        public Builder chatModel(ChatModel v) {
            this.chatModel = v;
            return this;
        }

        public Builder semanticMemoryManager(SemanticMemoryManager v) {
            this.semanticMemoryManager = v;
            return this;
        }

        public Builder enableProfileMemory(boolean v) {
            this.enableProfileMemory = v;
            return this;
        }

        public Builder enableSession(boolean v) {
            this.enableSession = v;
            return this;
        }

        public Builder enableTrace(boolean v) {
            this.enableTrace = v;
            return this;
        }

        public Builder askUserToolName(String v) {
            this.askUserToolName = v;
            return this;
        }

        public Builder stageOutputProviders(List<StageOutputProvider> v) {
            this.stageOutputProviders = v;
            return this;
        }

        public Builder thinkingMode(ThinkingMode v) {
            this.thinkingMode = v;
            return this;
        }

        public Builder maxRetries(int v) {
            this.maxRetries = v;
            return this;
        }

        public Builder contextCompactor(ContextCompactor v) {
            this.contextCompactor = v;
            return this;
        }

        public Builder deferredToolRegistry(DeferredToolRegistry v) {
            this.deferredToolRegistry = v;
            return this;
        }

        public Builder advisors(List<Advisor> v) {
            this.advisors = v;
            return this;
        }

        public Builder traceStore(TraceStore v) {
            this.traceStore = v;
            return this;
        }

        public Builder stateStore(PauseStateStore v) {
            this.stateStore = v;
            return this;
        }

        public AgentLoopExecutor build() {
            Objects.requireNonNull(chatClient, "chatClient must not be null");
            return new AgentLoopExecutor(this);
        }
    }

    /**
     * 非流式执行 ReAct 循环，返回 AgentResult。
     *
     * <p>REASONING_CONTENT 模式下，Spring AI 非流式路径不映射 reasoning_content 到 metadata，
     * 因此内部改用流式调用并通过 Thinking 事件收集思考内容。
     *
     * @param query  用户消息
     * @param params 调用参数
     * @return AgentResult（Completed 或 Paused）
     */
    public AgentResult call(String query, RunnableParams params) {
        String conversationId = params != null ? params.getConversationId() : null;
        if (taskManager != null && conversationId != null) {
            if (taskManager.registerTask(conversationId, null) == null) {
                return new AgentResult.Failed("该会话正在执行中，请稍后再试: " + conversationId, AgentErrorCode.CONCURRENT_EXECUTION);
            }
        }
        try {
            return callViaStreamForResult(query, params);
        } finally {
            if (taskManager != null && conversationId != null) {
                taskManager.removeTask(conversationId);
            }
        }
    }

    /**
     * 初始化执行上下文：始终生成 sessionId（作为本次执行的唯一标识，
     * 用于 agentx_session 主键、文件关联、trace 等场景），并按需创建 TraceManager。
     * <p>
     * 注意：sessionId 生成不再依赖 trace 是否启用——trace 关闭时 sessionId 仍可用，
     * 只是 TraceManager 不创建而已。
     */
    private void initTrace(AgentExecutionContext execCtx, RunnableParams params) {
        // 始终生成 sessionId（即使 trace 未启用，会话存储与外部关联仍需要稳定 ID）
        long sessionId = IdWorker.getId();
        execCtx.setSessionId(sessionId);

        // trace 仅在启用且具备存储条件时初始化（保持原有行为）
        if (traceStore == null || !enableTrace) return;
        String conversationId = params != null ? params.getConversationId() : null;
        if (conversationId == null) return;
        execCtx.setTraceManager(new TraceManager(traceStore, sessionId, conversationId));
    }

    /**
     * 从暂停恢复时恢复 sessionId 和累计 token。
     */
    private void initTraceFromResume(AgentExecutionContext execCtx, PauseState state) {
        if (traceStore == null || !enableTrace) return;
        RunnableParams params = state.getParams();
        String conversationId = params != null ? params.getConversationId() : null;
        execCtx.setSessionId(state.getSessionId());
        execCtx.setTraceManager(new TraceManager(traceStore, state.getSessionId(), conversationId));
        execCtx.restoreTokens(state.getTotalPromptTokens(), state.getTotalCompletionTokens());
    }

    /**
     * 记录一次 LLM 调用 trace（成功）。
     */
    private void recordTrace(AgentExecutionContext execCtx, int round, String requestJson,
                             String outputData, String think,
                             long promptTokens, long completionTokens, long durationMs) {
        execCtx.accumulateTokens(promptTokens, completionTokens);
        log.debug("[TRACE] round={}, prompt={}, completion={}, totalPrompt={}, totalCompletion={}",
                round, promptTokens, completionTokens,
                execCtx.getTotalPromptTokens(), execCtx.getTotalCompletionTokens());
        TraceManager tm = execCtx.getTraceManager();
        if (tm == null) return;
        tm.trace(round, requestJson, outputData, think,
                (int) promptTokens, (int) completionTokens, durationMs);
    }

    /**
     * 将工具调用列表序列化为 JSON，用作 trace 的 outputData。
     */
    private String serializeToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        try {
            var list = toolCalls.stream().map(tc -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", tc.id());
                map.put("type", tc.type());
                map.put("name", tc.name());
                map.put("arguments", tc.arguments());
                return map;
            }).toList();
            return objectMapper.writeValueAsString(Map.of("tool_calls", list));
        } catch (Exception e) {
            log.debug("[TraceStore] Failed to serialize tool calls: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从非流式 ChatResponse 中提取 promptTokens / completionTokens。
     */
    /**
     * 是否结构化输出（outputType != null）。
     * 结构化输出场景最终 answer 必须纯净，think 也只取最终轮避免干扰。
     */
    private boolean isStructuredOutput(RunnableParams params) {
        return params != null && params.getOutputType() != null;
    }

    /**
     * 累积最终轮 think 并按 outputType 决定入库/返回值：
     * 结构化输出只取最终轮（roundThink），非结构化取所有轮累积值。
     */
    private String resolveFinalThink(RunnableParams params, String roundThink, AgentExecutionContext execCtx) {
        execCtx.accumulateThink(roundThink);
        return isStructuredOutput(params) ? roundThink : execCtx.getAccumulatedThink();
    }

    /**
     * REASONING_CONTENT 模式：内部使用流式调用收集完整结果。
     *
     * <p>流式路径正确映射 reasoning_content 到 metadata（Thinking 事件），
     * 非流式路径缺失该映射，因此统一走流式收集后转换为 AgentResult。
     *
     * <p>不直接调用 {@link #stream}，而是自行构建消息、调度流式轮次，
     * 在 blockLast 返回后同步完成入库，避免 wrapStreamFlux 的 doFinally 时序问题导致历史丢失。
     */
    private AgentResult callViaStreamForResult(String query, RunnableParams params) {
        List<Message> messages = messageBuilder.buildInitialMessages(query, params);
        Sinks.Many<AgentStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        AgentExecutionContext execCtx = new AgentExecutionContext(query, params);
        initTrace(execCtx, params);
        AtomicLong roundCounter = new AtomicLong(0);

        registerInterruptContext(messages, sink, params, query, execCtx, roundCounter);

        return blockForResult(messages, sink, roundCounter, params, execCtx, query);
    }

    /**
     * 通用 sink 阻塞收集逻辑：scheduleRound → doOnNext 累积 → blockLast → AgentResult。
     * 供 {@link #callViaStreamForResult} 和 {@link #resumeViaStreamForResult} 共用，
     * 消除 doOnNext 事件累积的重复代码。
     */
    private AgentResult blockForResult(List<Message> messages,
                                       Sinks.Many<AgentStreamEvent> sink,
                                       AtomicLong roundCounter,
                                       RunnableParams params,
                                       AgentExecutionContext execCtx,
                                       String query) {
        scheduleRound(messages, sink, roundCounter, params, execCtx, query);

        StringBuilder answer = new StringBuilder();
        StringBuilder think = new StringBuilder();
        Map<String, Object> stageOutputs = new HashMap<>();
        PauseState[] pauseHolder = {null};
        String[] errorHolder = {null};

        sink.asFlux()
                .doOnNext(event -> {
                    switch (event) {
                        case AgentStreamEvent.Text t -> answer.append(t.content());
                        case AgentStreamEvent.Thinking t -> think.append(t.content());
                        case AgentStreamEvent.StageOutput so -> stageOutputs.put(so.stage(), so.data());
                        case AgentStreamEvent.Paused p -> pauseHolder[0] = p.state();
                        case AgentStreamEvent.Error e -> errorHolder[0] = e.message();
                        // 工具调用轮：该轮 Text 是思考性正文而非最终答案，
                        // answer 始终清空（最终答案只保留最后一轮正文）；
                        // think 仅结构化输出时清空（避免干扰），非结构化时累积所有轮思考
                        case AgentStreamEvent.ToolStart ts -> {
                            answer.setLength(0);
                            if (isStructuredOutput(params)) {
                                think.setLength(0);
                            }
                        }
                        default -> {
                        }
                    }
                })
                .blockLast();

        if (errorHolder[0] != null) {
            return new AgentResult.Failed(errorHolder[0], AgentErrorCode.LLM_CALL_FAILED);
        }
        if (pauseHolder[0] != null) {
            return new AgentResult.Paused(pauseHolder[0]);
        }

        String finalAnswer = answer.toString();
        if (params != null && params.getOutputType() != null) {
            finalAnswer = JsonRepairUtil.fixJson(finalAnswer);
        }

        return new AgentResult.Completed(
                finalAnswer,
                think.length() > 0 ? think.toString() : null,
                stageOutputs);
    }

    /**
     * 从暂停状态恢复执行（非流式）。
     *
     * @param state       暂停状态
     * @param toolResults 工具调用结果 (key=toolCallId, value=结果文本)
     * @return AgentResult（Completed 或 Paused）
     */
    public AgentResult resume(PauseState state, Map<String, String> toolResults) {
        List<Message> messages = buildResumeMessages(state, toolResults);

        String conversationId = state.getParams() != null ? state.getParams().getConversationId() : null;
        if (taskManager != null && conversationId != null) {
            if (taskManager.registerTask(conversationId, null) == null) {
                return new AgentResult.Failed("该会话正在执行中，请稍后再试: " + conversationId,
                        AgentErrorCode.CONCURRENT_EXECUTION);
            }
        }
        try {
            AgentResult result = resumeViaStreamForResult(messages, state);
            if (result instanceof AgentResult.Completed && stateStore != null && conversationId != null) {
                stateStore.delete(conversationId);
            }
            return result;
        } finally {
            if (taskManager != null && conversationId != null) {
                taskManager.removeTask(conversationId);
            }
        }
    }

    /**
     * 流式-backed 恢复执行：创建 sink、恢复 trace、委托给 {@link #blockForResult}。
     */
    private AgentResult resumeViaStreamForResult(List<Message> messages, PauseState state) {
        Sinks.Many<AgentStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicLong roundCounter = new AtomicLong(state.getCurrentRound());
        AgentExecutionContext execCtx = new AgentExecutionContext(state.getQuery(), state.getParams());
        initTraceFromResume(execCtx, state);

        registerInterruptContext(messages, sink, state.getParams(), state.getQuery(), execCtx, roundCounter);

        return blockForResult(messages, sink, roundCounter, state.getParams(), execCtx, state.getQuery());
    }

    /**
     * 根据暂停原因和阶段选择工具结果解析策略。
     *
     * <p>USER_INTERRUPT 在 TOOL_EXECUTION 阶段：重新执行已知工具，外部工具注入占位响应。
     * HITL / LLM_STREAMING 阶段：走原有 {@link ToolCallExecutor#resolveResumeToolResult}。
     */
    private String resolveToolResult(PendingToolCall ptc,
                                     AssistantMessage.ToolCall toolCall,
                                     Map<String, String> toolResults,
                                     PauseState state) {
        if (state.getReason() == PauseReason.USER_INTERRUPT
                && state.getSafePoint() == SafePoint.TOOL_EXECUTION) {
            return toolCallExecutor.resolveInterruptToolResult(ptc, toolCall, state.getParams());
        }
        return toolCallExecutor.resolveResumeToolResult(ptc, toolCall, toolResults, state.getParams());
    }

    /**
     * 从 PauseState 恢复消息列表，注入 pending tool calls 的 ToolResponseMessage。
     * 供 {@link #resume} 和 {@link #resumeStream} 共用，消除重复的消息恢复代码。
     */
    private List<Message> buildResumeMessages(PauseState state, Map<String, String> toolResults) {
        List<Message> messages = new ArrayList<>(state.getMessages());
        for (PendingToolCall ptc : state.getPendingToolCalls()) {
            AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                    ptc.id(), "function", ptc.name(), ptc.arguments());
            String result = resolveToolResult(ptc, toolCall, toolResults, state);
            toolCallExecutor.addNormalToolMessage(toolCall, result, messages);
        }
        return messages;
    }

    /**
     * 流式执行 ReAct 循环，返回 AgentStreamEvent 流。
     *
     * @param query  用户消息
     * @param params 调用参数
     * @return AgentStreamEvent 流（Text 或 Paused）
     */
    public Flux<AgentStreamEvent> stream(String query, RunnableParams params) {
        String conversationId = params != null ? params.getConversationId() : null;
        List<Message> messages = messageBuilder.buildInitialMessages(query, params);

        Sinks.Many<AgentStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();

        if (taskManager != null && conversationId != null) {
            // 注册真正的 sink（非 null），让 stopTask 的 tryEmitComplete 能正常结束下游 Flux
            AgentTaskManager.TaskInfo taskInfo = taskManager.registerTask(conversationId, sink);
            if (taskInfo == null) {
                return Flux.error(new AgentException(AgentErrorCode.CONCURRENT_EXECUTION,
                        "该会话正在执行中，请稍后再试: " + conversationId));
            }
        }

        // AgentStart + AFTER_START providers
        AgentExecutionContext execCtx = new AgentExecutionContext(query, params);
        initTrace(execCtx, params);
        sink.tryEmitNext(new AgentStreamEvent.AgentStart());
        if (!stageManager.isEmpty()) {
            stageManager.afterStart(execCtx.toStageContext(), sink::tryEmitNext);
        }

        AtomicLong roundCounter = new AtomicLong(0);

        registerInterruptContext(messages, sink, params, query, execCtx, roundCounter);

        // disposable 的注册由 scheduleRound 内部每轮刷新负责，此处不再手动 setDisposable
        scheduleRound(messages, sink, roundCounter, params, execCtx, query);

        return wrapStreamFlux(sink, conversationId, roundCounter, params, query);
    }

    /**
     * 从暂停状态恢复流式执行。
     *
     * @param state       暂停状态
     * @param toolResults 工具调用结果
     * @return AgentStreamEvent 流
     */
    public Flux<AgentStreamEvent> resumeStream(PauseState state, Map<String, String> toolResults) {
        List<Message> messages = buildResumeMessages(state, toolResults);

        String conversationId = state.getParams() != null ? state.getParams().getConversationId() : null;

        Sinks.Many<AgentStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();

        if (taskManager != null && conversationId != null) {
            // 注册真正的 sink（非 null），让 stopTask 的 tryEmitComplete 能正常结束下游 Flux
            AgentTaskManager.TaskInfo taskInfo = taskManager.registerTask(conversationId, sink);
            if (taskInfo == null) {
                return Flux.error(new AgentException(AgentErrorCode.CONCURRENT_EXECUTION,
                        "该会话正在执行中，请稍后再试: " + conversationId));
            }
        }

        String query = state.getQuery();
        AtomicLong roundCounter = new AtomicLong(state.getCurrentRound());
        AgentExecutionContext execCtx = new AgentExecutionContext(query, state.getParams());
        initTraceFromResume(execCtx, state);

        // 发射 ResumeStart 事件，通知前端"恢复执行开始"
        sink.tryEmitNext(new AgentStreamEvent.ResumeStart(
                conversationId,
                state.getCurrentRound(),
                state.getInterruptedAt(),
                state.getReason()));

        registerInterruptContext(messages, sink, state.getParams(), query, execCtx, roundCounter);

        // disposable 的注册由 scheduleRound 内部每轮刷新负责，此处不再手动 setDisposable
        scheduleRound(messages, sink, roundCounter, state.getParams(), execCtx, query);

        // 正常完成时清理快照（Paused 不能删，会通过 UPSERT 覆盖）
        var wasPaused = new java.util.concurrent.atomic.AtomicBoolean(false);
        return wrapStreamFlux(sink, conversationId, roundCounter, state.getParams(), query)
                .doOnNext(event -> {
                    if (event instanceof AgentStreamEvent.Paused) {
                        wasPaused.set(true);
                    }
                })
                .doOnComplete(() -> {
                    if (!wasPaused.get() && stateStore != null && conversationId != null) {
                        stateStore.delete(conversationId);
                    }
                });
    }

    /**
     * 为流式 Flux 统一添加错误处理和清理。
     *
     * <p>注意：save 逻辑（chatMemory + memoryPersistor）已移到 finishRound / forceFinalStream 中，
     * 在 tryEmitComplete() 之前执行，避免 doFinally 时序问题导致 JVM 退出时数据丢失。
     */
    private Flux<AgentStreamEvent> wrapStreamFlux(Sinks.Many<AgentStreamEvent> sink,
                                                  String conversationId,
                                                  AtomicLong roundCounter,
                                                  RunnableParams params,
                                                  String query) {
        return sink.asFlux()
                .doOnError(err -> handleStreamError(conversationId, err))
                .doFinally(signal -> {
                    log.debug("Stream terminated: conversationId={}, signal={}", conversationId, signal);
                    if (taskManager != null && conversationId != null) {
                        taskManager.stopTask(conversationId);
                    }
                });
    }

    /**
     * 流式完成时保存会话历史和长期记忆（在 tryEmitComplete 之前调用，避免 doFinally 时序问题）。
     * <p>
     * 显式传入 sessionId，使 agentx_session 主键与本次执行的 trace、文件关联等场景对齐。
     */
    private void saveStreamHistory(String conversationId, RunnableParams params,
                                   String query, String answer, String think, String timelineJson,
                                   long sessionId) {
        if (conversationId == null) return;
        messageBuilder.saveToChatMemory(query, answer, think, timelineJson, conversationId,
                params != null ? params.getUserId() : null, sessionId);
        memoryPersistor.persist(params, query, answer, conversationId);
    }

    private void handleStreamError(String conversationId, Throwable err) {
        log.error("\n\n Stream error: conversationId={}", conversationId, err);
        // 清理统一由 doFinally → stopTask 处理，避免提前 remove 导致内层 Disposable 无法 dispose
    }

    /**
     * 持久化暂停状态到 {@link PauseStateStore}（若配置）。
     * 同时用于 HITL 暂停和 USER_INTERRUPT 中断。
     * 失败仅记录日志，不影响主流程（Paused 事件仍会发射）。
     */
    private void persistPauseState(PauseState state) {
        if (stateStore == null || state == null) {
            return;
        }
        try {
            stateStore.save(state);
        } catch (Exception e) {
            log.warn("[AgentLoopExecutor] Failed to persist pause state: {}", e.getMessage());
        }
    }

    /**
     * 注册中断上下文和处理器，供 {@link #stream}、{@link #resumeStream}、
     * {@link #callViaStreamForResult}、{@link #resumeViaStreamForResult} 共用。
     */
    private void registerInterruptContext(List<Message> messages, Sinks.Many<AgentStreamEvent> sink,
                                          RunnableParams params, String query,
                                          AgentExecutionContext execCtx, AtomicLong roundCounter) {
        String conversationId = params != null ? params.getConversationId() : null;
        this.interruptContext = new InterruptContext(messages, sink, params, query);
        if (taskManager != null && conversationId != null) {
            taskManager.setInterruptHandler(conversationId, msg -> {
                PauseState snapshot = interruptContext.buildSnapshot(msg, execCtx, roundCounter.get());
                persistPauseState(snapshot);
                interruptContext.emitPausedAndComplete(snapshot);
            });
        }
    }

    private Disposable scheduleRound(List<Message> messages, Sinks.Many<AgentStreamEvent> sink,
                                     AtomicLong roundCounter, RunnableParams params,
                                     AgentExecutionContext execCtx, String query) {
        return scheduleRound(messages, sink, roundCounter, params, execCtx, query, 0);
    }

    private Disposable scheduleRound(List<Message> messages, Sinks.Many<AgentStreamEvent> sink,
                                     AtomicLong roundCounter, RunnableParams params,
                                     AgentExecutionContext execCtx, String query, int retryAttempt) {
        long round = roundCounter.incrementAndGet();
        String conversationId = params != null ? params.getConversationId() : null;
        log.debug("Scheduling round: {}, conversationId={}, retryAttempt={}", round, conversationId, retryAttempt);

        // 进入 LLM_STREAMING 阶段，可被中断
        if (interruptContext != null) {
            interruptContext.enterLlmStreaming();
        }

        RoundState roundState = new RoundState();
        long startTime = System.currentTimeMillis();

        // 上下文压缩（每轮 LLM 调用前执行）
        if (contextCompactor != null) {
            contextCompactor.compact(messages, query);
        }

        Disposable disposable = llmInvoker.buildRoundChatClient().prompt()
                .messages(messages)
                .stream()
                .chatClientResponse()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(ccResp -> {
                    // 捕获 Advisor chain context（PauseAdvisor 在聚合响应中设置 PAUSE_REQUIRED）
                    Map<String, Object> ctx = ccResp.context();
                    if (ctx != null && !ctx.isEmpty()) {
                        roundState.advisorContext = ctx;
                    }
                    // 跳过 PauseAdvisor 聚合响应，避免重复处理 tool calls
                    if (ctx == null || !Boolean.TRUE.equals(ctx.get(PauseAdvisor.PAUSE_REQUIRED))) {
                        ChatResponse chunk = ccResp.chatResponse();
                        if (chunk != null) {
                            processChunk(chunk, sink, roundState, execCtx);
                        }
                    }
                })
                .doOnComplete(() -> {
                    long durationMs = System.currentTimeMillis() - startTime;
                    finishRound(messages, sink, roundState, roundCounter, params, execCtx, query, durationMs);
                })
                .onErrorResume(err -> {
                    llmInvoker.handleStreamError(err, retryAttempt, sink,
                            execCtx.getTimelineCollector(),
                            () -> scheduleRound(messages, sink, roundCounter, params, execCtx, query, retryAttempt + 1),
                            "LLM stream error");
                    return Flux.empty();
                })
                .subscribe();

        // 每轮都把 taskManager 持有的 disposable 刷新为当前轮 subscription。
        // 否则 stopTask 拿到的永远是第 1 轮（已完成、已 disposed）的 disposable，无法中断后续轮次。
        // 本方法是向 taskManager 注册 disposable 的唯一入口（含递归重试 / forceFinalStream / resume）。
        if (taskManager != null && conversationId != null) {
            taskManager.setDisposable(conversationId, disposable);
        }
        return disposable;
    }

    private void processChunk(ChatResponse chunk, Sinks.Many<AgentStreamEvent> sink,
                              RoundState state, AgentExecutionContext execCtx) {
        if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
            return;
        }

        // 捕获 token 用量和结束原因（流式响应通常在最后一个 chunk 中包含）
        if (chunk.getMetadata() != null && chunk.getMetadata().getUsage() != null) {
            var usage = chunk.getMetadata().getUsage();
            state.promptTokens = usage.getPromptTokens();
            state.completionTokens = usage.getCompletionTokens();
        }
        if (chunk.getResult() != null && chunk.getResult().getMetadata() != null) {
            String reason = chunk.getResult().getMetadata().getFinishReason();
            if (reason != null && !reason.isEmpty()) {
                state.finishReason = reason;
            }
        }

        List<AssistantMessage.ToolCall> toolCalls = chunk.getResult().getOutput().getToolCalls();

        if (toolCalls != null && !toolCalls.isEmpty()) {
            state.mode = RoundMode.TOOL_CALL;
            for (AssistantMessage.ToolCall incoming : toolCalls) {
                mergeToolCall(state, incoming);
            }
            // tool call chunk 中也可能携带 reasoning_content（某些模型在思考后直接调用工具）
            thinkingModeProcessor.accumulateReasoningContent(chunk.getResult().getOutput(), state);
            return;
        }

        state.mode = RoundMode.TEXT;
        String text = chunk.getResult().getOutput().getText();

        // ThinkingMode 三模式分支：委托给 ThinkingModeProcessor
        TimelineCollector collector = execCtx.getTimelineCollector();
        if (thinkingMode == ThinkingMode.REASONING_CONTENT) {
            String reasoning = thinkingModeProcessor.extractReasoningContent(chunk.getResult().getOutput());
            thinkingModeProcessor.processReasoningChunk(reasoning, state, sink, collector);
        }
        thinkingModeProcessor.processStreamChunk(text, state, sink, collector);
    }

    private void finishRound(List<Message> messages, Sinks.Many<AgentStreamEvent> sink,
                             RoundState state, AtomicLong roundCounter, RunnableParams params,
                             AgentExecutionContext execCtx, String query, long durationMs) {
        String conversationId = params != null ? params.getConversationId() : null;
        int round = (int) roundCounter.get();
        String requestJson = state.advisorContext != null
                ? (String) state.advisorContext.get(RequestLoggingAdvisor.LLM_REQUEST_JSON) : null;

        if (state.promptTokens >= 0 || state.finishReason != null) {
            log.debug("LLM response detail: conversationId={}, promptTokens={}, completionTokens={}, finishReason={}",
                    conversationId, state.promptTokens, state.completionTokens, state.finishReason);
        }

        if (state.toolCalls.isEmpty()) {
            log.debug("No tool calls detected, stream completed: conversationId={}", conversationId);
            // 将累积的文本作为 AssistantMessage 添加到 messages
            if (!state.textBuffer.isEmpty()) {
                Map<String, Object> props = thinkingModeProcessor.buildReasoningProperties(state);
                messages.add(AssistantMessage.builder()
                        .content(state.textBuffer.toString())
                        .properties(props)
                        .build());
            }

            // 记录 trace（最终答案轮，trace 保持单轮 think）
            String roundThink = state.reasoningBuffer.length() > 0 ? state.reasoningBuffer.toString() : null;
            recordTrace(execCtx, round, requestJson, state.textBuffer.toString(),
                    roundThink, state.promptTokens, state.completionTokens, durationMs);

            // 保存会话历史 + 长期记忆（在 tryEmitComplete 之前，避免 doFinally 时序问题）
            // 入库 think：结构化输出只取最终轮，非结构化取所有轮累积值
            String finalThink = resolveFinalThink(params, roundThink, execCtx);
            String timelineJson = TimelineSerializer.toJson(execCtx.getTimelineCollector().getEntries());
            saveStreamHistory(conversationId, params, query, state.textBuffer.toString(), finalThink, timelineJson,
                    execCtx.getSessionId());

            // BEFORE_COMPLETE providers + Complete 事件
            if (execCtx != null) {
                execCtx.setAnswer(state.textBuffer.toString());
                if (!stageManager.isEmpty()) {
                    stageManager.beforeComplete(execCtx.toStageContext(), sink::tryEmitNext);
                }
            }
            EmitResult completeResult = sink.tryEmitNext(new AgentStreamEvent.Complete(
                    execCtx.getTotalPromptTokens(), execCtx.getTotalCompletionTokens(),
                    conversationId, execCtx.getSessionId(), null));
            log.debug("tryEmitNext(Complete) result={}, conversationId={}", completeResult, conversationId);
            EmitResult emitResult = sink.tryEmitComplete();
            log.debug("tryEmitComplete result={}, conversationId={}", emitResult, conversationId);
            return;
        }

        // 校验并修复不合法的 tool call arguments，防止后续 API 调用 400
        List<AssistantMessage.ToolCall> safeToolCalls = toolCallExecutor.sanitizeToolCalls(state.toolCalls);

        // tool call 路径：构建 AssistantMessage 时携带 reasoningContent（通过 properties 传递）
        Map<String, Object> props = thinkingModeProcessor.buildReasoningProperties(state);
        AssistantMessage assistantMsg = AssistantMessage.builder()
                .content(state.textBuffer.isEmpty() ? "" : state.textBuffer.toString())
                .toolCalls(safeToolCalls)
                .properties(props)
                .build();
        messages.add(assistantMsg);

        // 累积本轮思考（用于非结构化输出时最终入库；结构化输出时 resolveFinalThink 会改取最终轮）
        execCtx.accumulateThink(state.reasoningBuffer.length() > 0 ? state.reasoningBuffer.toString() : null);

        if (maxRounds > 0 && roundCounter.get() >= maxRounds) {
            log.debug("Max rounds reached, forcing final answer: conversationId={}", conversationId);
            // 记录 trace（工具调用轮，达到上限）
            recordTrace(execCtx, round, requestJson, serializeToolCalls(safeToolCalls), null,
                    state.promptTokens, state.completionTokens, durationMs);
            forceFinalStream(messages, sink, params, execCtx, query);
            return;
        }

        // === 流式暂停检查（通过 Advisor chain context） ===
        if (state.advisorContext != null
                && Boolean.TRUE.equals(state.advisorContext.get(PauseAdvisor.PAUSE_REQUIRED))) {
            List<PendingToolCall> pending = PauseAdvisor.getPendingTools(state.advisorContext);

            // 执行非拦截工具
            toolCallExecutor.executeNonPendingTools(safeToolCalls, pending, messages, params);

            PauseState pauseState = PauseState.builder()
                    .messages(List.copyOf(messages))
                    .currentRound((int) roundCounter.get())
                    .pendingToolCalls(pending)
                    .params(params)
                    .query(query)
                    .sessionId(execCtx.getSessionId())
                    .totalPromptTokens(execCtx.getTotalPromptTokens())
                    .totalCompletionTokens(execCtx.getTotalCompletionTokens())
                    .reason(PauseReason.HITL_TOOL_REQUEST)
                    .interruptedAt(System.currentTimeMillis())
                    .build();

            persistPauseState(pauseState);

            // 记录 trace（暂停前）
            recordTrace(execCtx, round, requestJson, serializeToolCalls(safeToolCalls), null,
                    state.promptTokens, state.completionTokens, durationMs);

            log.debug("Stream paused at round {}, pending tools: {}", roundCounter.get(), pending.size());
            sink.tryEmitNext(new AgentStreamEvent.Paused(pauseState));
            sink.tryEmitComplete();
            return;
        }

        // 记录 trace（工具调用轮）
        recordTrace(execCtx, round, requestJson, serializeToolCalls(safeToolCalls), null,
                state.promptTokens, state.completionTokens, durationMs);

        // 发射 ToolStart 事件
        for (AssistantMessage.ToolCall tc : safeToolCalls) {
            sink.tryEmitNext(new AgentStreamEvent.ToolStart(tc.name(), tc.id(), tc.arguments()));
            execCtx.getTimelineCollector().onToolStart(tc.name(), tc.id(), tc.arguments());
        }

        // 进入工具执行阶段：可被中断，pendingToolCalls 含正在执行的工具调用
        if (interruptContext != null) {
            interruptContext.enterToolExecution(safeToolCalls);
        }

        toolCallExecutor.executeToolCallsAsync(sink, safeToolCalls, messages, params, execCtx, () -> {
            scheduleRound(messages, sink, roundCounter, params, execCtx, query);
        });
    }

    private void mergeToolCall(RoundState state, AssistantMessage.ToolCall incoming) {
        for (int i = 0; i < state.toolCalls.size(); i++) {
            AssistantMessage.ToolCall existing = state.toolCalls.get(i);

            if (existing.id().equals(incoming.id())) {
                String mergedArgs = Objects.toString(existing.arguments(), "") +
                        Objects.toString(incoming.arguments(), "");

                state.toolCalls.set(i, new AssistantMessage.ToolCall(
                        existing.id(),
                        existing.type() != null ? existing.type() : "function",
                        existing.name(),
                        mergedArgs
                ));
                return;
            }
        }

        state.toolCalls.add(incoming);
    }

    private void forceFinalStream(List<Message> messages, Sinks.Many<AgentStreamEvent> sink,
                                  RunnableParams params, AgentExecutionContext execCtx, String query) {
        forceFinalStream(messages, sink, params, execCtx, query, 0);
    }

    private void forceFinalStream(List<Message> messages, Sinks.Many<AgentStreamEvent> sink,
                                  RunnableParams params, AgentExecutionContext execCtx, String query, int retryAttempt) {
        List<Message> newMessages = new ArrayList<>(messages);

        // 闭合最后一轮未执行的 tool_calls
        if (!newMessages.isEmpty() && newMessages.get(newMessages.size() - 1) instanceof AssistantMessage lastMsg) {
            if (lastMsg.getToolCalls() != null && !lastMsg.getToolCalls().isEmpty()) {
                for (AssistantMessage.ToolCall tc : lastMsg.getToolCalls()) {
                    toolCallExecutor.addNormalToolMessage(tc, "Agent maximum rounds reached. Tool execution skipped.", newMessages);
                }
            }
        }

        StringBuilder answerBuffer = new StringBuilder();
        boolean[] inThink = {false};
        final long[] finalUsage = {0, 0};

        Disposable disposable = llmInvoker.buildRoundChatClient().prompt()
                .messages(newMessages)
                .stream()
                .chatResponse()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> {
                    String text = chunk.getResult().getOutput().getText();

                    // 捕获 token 用量（最后一个 chunk 包含）
                    if (chunk.getMetadata() != null && chunk.getMetadata().getUsage() != null) {
                        var usage = chunk.getMetadata().getUsage();
                        finalUsage[0] = usage.getPromptTokens();
                        finalUsage[1] = usage.getCompletionTokens();
                    }

                    if (thinkingMode == ThinkingMode.REASONING_CONTENT) {
                        String reasoning = thinkingModeProcessor.extractReasoningContent(chunk.getResult().getOutput());
                        if (reasoning != null && !reasoning.isEmpty()) {
                            sink.tryEmitNext(new AgentStreamEvent.Thinking(reasoning));
                            execCtx.accumulateThink(reasoning);
                            execCtx.getTimelineCollector().onThinking(reasoning);
                        }
                    }
                    thinkingModeProcessor.processForceFinalChunk(text, inThink, answerBuffer, sink,
                            execCtx.getTimelineCollector());
                })
                .doOnComplete(() -> {
                    String conversationId = params != null ? params.getConversationId() : null;
                    // 累加 forceFinal 轮的 token
                    execCtx.accumulateTokens(finalUsage[0], finalUsage[1]);
                    // BEFORE_COMPLETE providers + save + Complete
                    if (execCtx != null) {
                        execCtx.setAnswer(answerBuffer.toString());
                        if (!stageManager.isEmpty()) {
                            stageManager.beforeComplete(execCtx.toStageContext(), sink::tryEmitNext);
                        }
                    }
                    // 保存会话历史 + 长期记忆（在 tryEmitComplete 之前）
                    // 入库 think：结构化输出不入库（保持最终答案纯净），非结构化取所有轮累积值
                    String finalThink = isStructuredOutput(params) ? null : execCtx.getAccumulatedThink();
                    String timelineJson = TimelineSerializer.toJson(execCtx.getTimelineCollector().getEntries());
                    saveStreamHistory(conversationId, params, query, answerBuffer.toString(), finalThink, timelineJson,
                            execCtx.getSessionId());
                    sink.tryEmitNext(new AgentStreamEvent.Complete(
                            execCtx.getTotalPromptTokens(), execCtx.getTotalCompletionTokens(),
                            conversationId, execCtx.getSessionId(), null));
                    sink.tryEmitComplete();
                })
                .onErrorResume(err -> llmInvoker.handleStreamError(err, retryAttempt, sink,
                        execCtx.getTimelineCollector(),
                        () -> forceFinalStream(messages, sink, params, execCtx, query, retryAttempt + 1),
                        "forceFinal stream error"))
                .subscribe();

        // 同 scheduleRound：每轮刷新 disposable，保证 stopTask 可中断当前在飞的 forceFinal 流。
        String conversationId = params != null ? params.getConversationId() : null;
        if (taskManager != null && conversationId != null) {
            taskManager.setDisposable(conversationId, disposable);
        }
    }

}
