package com.agentx.ai.core.agent.internal;

import com.agentx.ai.core.advisors.PauseAdvisor;
import com.agentx.ai.core.context.ContextCompactor;
import com.agentx.ai.core.exception.AgentErrorCode;
import com.agentx.ai.core.exception.AgentException;
import com.agentx.ai.core.memory.semantic.SemanticMemoryManager;
import com.agentx.ai.core.memory.store.MemoryStore;
import com.agentx.ai.core.memory.util.MemoryInjector;
import com.agentx.ai.core.memory.util.MemoryPersistor;
import com.agentx.ai.core.model.*;
import com.agentx.ai.core.prompt.PromptConstants;
import com.agentx.ai.core.stage.AgentExecutionContext;
import com.agentx.ai.core.stage.StageOutputManager;
import com.agentx.ai.core.stage.ThinkTagParser;
import com.agentx.ai.core.tools.toolsearch.DeferredToolRegistry;
import com.agentx.ai.core.utils.JsonRepairUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;
import reactor.core.scheduler.Schedulers;

import java.util.*;
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

    /**
     * 重试间隔（毫秒），内置默认值
     */
    private final int maxRounds;
    private final Map<String, ToolCallback> toolMap;
    private final PauseAdvisor pauseAdvisor;
    private final AgentTaskManager taskManager;
    private final MemoryInjector memoryInjector;
    private final MemoryPersistor memoryPersistor;
    private final LoopMessageBuilder messageBuilder;
    private final StageOutputManager stageManager;
    private final ThinkingMode thinkingMode;
    private final ThinkingModeProcessor thinkingModeProcessor;
    private final ToolCallExecutor toolCallExecutor;
    private final com.agentx.ai.core.agent.internal.LlmInvoker llmInvoker;
    private final ContextCompactor contextCompactor;

    private AgentLoopExecutor(Builder builder) {
        this.maxRounds = builder.maxRounds;
        this.pauseAdvisor = builder.pauseAdvisor;
        this.taskManager = builder.taskManager;
        this.thinkingMode = builder.thinkingMode;
        this.thinkingModeProcessor = new ThinkingModeProcessor(builder.thinkingMode);
        this.contextCompactor = builder.contextCompactor;

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
                builder.memoryStore, builder.semanticMemoryManager, builder.profileMemoryEnabled);

        // 记忆持久化器
        this.memoryPersistor = new MemoryPersistor(
                builder.memoryStore, builder.chatModel,
                builder.semanticMemoryManager, builder.profileMemoryEnabled);

        // 消息构建器
        this.messageBuilder = new LoopMessageBuilder(
                builder.instructions, builder.chatMemory,
                memoryInjector, builder.thinkingMode, builder.deferredToolRegistry);

        // 工具调用执行器
        this.toolCallExecutor = new ToolCallExecutor(toolMap, new ObjectMapper(),
                builder.pauseAdvisor, stageManager);
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
        private boolean profileMemoryEnabled = true;
        private PauseAdvisor pauseAdvisor;
        private List<StageOutputProvider> stageOutputProviders;
        private ThinkingMode thinkingMode = ThinkingMode.DISABLED;
        private int maxRetries = 3;
        private ContextCompactor contextCompactor;
        private DeferredToolRegistry deferredToolRegistry;
        private List<Advisor> advisors;

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

        public Builder profileMemoryEnabled(boolean v) {
            this.profileMemoryEnabled = v;
            return this;
        }

        public Builder pauseAdvisor(PauseAdvisor v) {
            this.pauseAdvisor = v;
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
        if (thinkingMode == ThinkingMode.REASONING_CONTENT) {
            return callViaStreamForResult(query, params);
        }
        List<Message> messages = messageBuilder.buildInitialMessages(query, params);
        return runLoop(messages, 0, params, query);
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
        AtomicLong roundCounter = new AtomicLong(0);

        scheduleRound(messages, sink, roundCounter, params, execCtx, query);

        StringBuilder answer = new StringBuilder();
        StringBuilder think = new StringBuilder();
        Map<String, Object> stageOutputs = new HashMap<>();
        PauseState[] pauseHolder = {null};

        sink.asFlux()
                .doOnNext(event -> {
                    switch (event) {
                        case AgentStreamEvent.Text t -> answer.append(t.content());
                        case AgentStreamEvent.Thinking t -> think.append(t.content());
                        case AgentStreamEvent.StageOutput so -> stageOutputs.put(so.stage(), so.data());
                        case AgentStreamEvent.Paused p -> pauseHolder[0] = p.state();
                        default -> {
                        }
                    }
                })
                .blockLast();

        if (pauseHolder[0] != null) {
            return new AgentResult.Paused(pauseHolder[0]);
        }

        // 同步完成入库（不经过 wrapStreamFlux 的 doFinally）
        String conversationId = params != null ? params.getConversationId() : null;
        String userId = params != null ? params.getUserId() : null;
        messageBuilder.saveToChatMemory(query, answer.toString(), think.length() > 0 ? think.toString() : null, conversationId, userId);
        memoryPersistor.persist(params, query, answer.toString(), conversationId);

        return new AgentResult.Completed(
                answer.toString(),
                think.length() > 0 ? think.toString() : null,
                stageOutputs);
    }

    /**
     * 从暂停状态恢复执行。
     *
     * @param state       暂停状态
     * @param toolResults 工具调用结果 (key=toolCallId, value=结果文本)
     * @return AgentResult（Completed 或 Paused）
     */
    public AgentResult resume(PauseState state, Map<String, String> toolResults) {
        List<Message> messages = new ArrayList<>(state.getMessages());

        // 注入暂停工具的 ToolResponseMessage
        for (PendingToolCall ptc : state.getPendingToolCalls()) {
            AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                    ptc.id(), "function", ptc.name(), ptc.arguments());
            String result = toolCallExecutor.resolveResumeToolResult(ptc, toolCall, toolResults, state.getParams());
            toolCallExecutor.addNormalToolMessage(toolCall, result, messages);
        }

        return runLoop(messages, state.getCurrentRound() + 1, state.getParams(),
                state.getQuery());
    }

    /**
     * 统一的 ReAct 循环。
     */
    private AgentResult runLoop(List<Message> messages, int startRound,
                                RunnableParams params, String query) {
        try {
            return doRunLoop(messages, startRound, params, query);
        } catch (AgentException e) {
            return new AgentResult.Failed(e.getMessage(), e.getCode());
        }
    }

    private AgentResult doRunLoop(List<Message> messages, int startRound,
                                  RunnableParams params, String query) {
        AgentExecutionContext execCtx = new AgentExecutionContext(query, params);
        Map<String, Object> stageOutputs = new HashMap<>();

        for (int round = startRound; round < maxRounds; round++) {
            log.debug("Agent loop round: {}", round);

            // 上下文压缩（每轮 LLM 调用前执行）
            if (contextCompactor != null) {
                contextCompactor.compact(messages, query);
            }

            ChatClientResponse ccResponse = llmInvoker.callLlm(messages);

            ChatResponse response = ccResponse.chatResponse();

            List<AssistantMessage.ToolCall> toolCalls = response.getResult().getOutput().getToolCalls();

            if (toolCalls != null && !toolCalls.isEmpty()) {
                // 直接使用原始 AssistantMessage（可能是 DeepSeekAssistantMessage 等子类型）
                // reasoningContent 等模型特定字段保留在子类型中，不做包装转换
                // text 为 null 的场景由各 ChatModel 实现自行兼容
                messages.add(response.getResult().getOutput());

                // === 暂停检查 (PauseAdvisor 通过 context 标记) ===
                if (Boolean.TRUE.equals(ccResponse.context().get(PauseAdvisor.PAUSE_REQUIRED))) {
                    List<PendingToolCall> pending = PauseAdvisor.getPendingTools(ccResponse.context());

                    // 执行非拦截工具
                    toolCallExecutor.executeNonPendingTools(toolCalls, pending, messages, params);

                    PauseState pauseState = PauseState.builder()
                            .messages(List.copyOf(messages))
                            .currentRound(round)
                            .pendingToolCalls(pending)
                            .params(params)
                            .query(query)
                            .build();

                    log.debug("Agent paused at round {}, pending tools: {}", round, pending.size());
                    return new AgentResult.Paused(pauseState);
                }

                toolCallExecutor.executeToolCallsWithStage(toolCalls, messages, params, execCtx, stageOutputs);
                log.debug("Tool calls executed: {}, continuing to next round", toolCalls.size());
                continue;
            }

            return completeWithAnswer(response, execCtx, stageOutputs, params, query);
        }

        // 达到最大轮次，强制生成最终答案
        ChatResponse forced = forceFinalAnswer(messages, params);
        return completeWithAnswer(forced, execCtx, stageOutputs, params, query);
    }

    /**
     * 非流式路径：完成最终答案的后处理（分离 answer/think、保存、返回）。
     */
    private AgentResult completeWithAnswer(ChatResponse chatResponse, AgentExecutionContext execCtx,
                                           Map<String, Object> stageOutputs,
                                           RunnableParams params, String query) {
        String answer = chatResponse.getResult().getOutput().getText();
        AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
        execCtx.setAnswer(answer);
        collectBeforeComplete(execCtx, stageOutputs);

        // 分离 answer 和 think
        String think;
        doPostProcess(answer, assistantMessage, params, query);
        if (thinkingMode == ThinkingMode.REASONING_CONTENT) {
            think = thinkingModeProcessor.extractReasoningContent(assistantMessage);
        } else {
            think = thinkingModeProcessor.extractThinkContent(answer);
        }
        answer = thinkingModeProcessor.stripThinkTagsIfNeeded(answer);
        if (params != null && params.getOutputType() != null) {
            answer = JsonRepairUtil.fixJson(answer);
        }
        return new AgentResult.Completed(answer, think, stageOutputs);
    }

    /**
     * 收集 BEFORE_COMPLETE 阶段输出到 Map（非流式路径）。
     */
    private void collectBeforeComplete(AgentExecutionContext execCtx, Map<String, Object> stageOutputs) {
        if (stageManager.isEmpty()) {
            return;
        }
        stageManager.beforeComplete(execCtx.toStageContext(), event -> {
            if (event instanceof AgentStreamEvent.StageOutput so && so.data() != null) {
                stageOutputs.put(so.stage(), so.data());
            }
        });
    }

    /**
     * 后处理：保存会话历史 + 长期记忆提取。
     */
    private void doPostProcess(String answer, AssistantMessage assistantMessage, RunnableParams params, String query) {
        String conversationId = params != null ? params.getConversationId() : null;
        String userId = params != null ? params.getUserId() : null;

        // 分离 think 和正文
        String cleanAnswer;
        String think;
        if (thinkingMode == ThinkingMode.REASONING_CONTENT) {
            // reasoning_content 模式：answer 已是纯正文，think 从消息中提取
            cleanAnswer = answer;
            think = thinkingModeProcessor.extractReasoningContent(assistantMessage);
        } else {
            // THINK_TAG 或 DISABLED：从 content 中分离
            cleanAnswer = ThinkTagParser.stripThinkTags(answer);
            think = thinkingModeProcessor.extractThinkContent(answer);
        }
        messageBuilder.saveToChatMemory(query, cleanAnswer, think, conversationId, userId);

        memoryPersistor.persist(params, query, answer, conversationId);
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
            AgentTaskManager.TaskInfo taskInfo = taskManager.registerTask(conversationId, null);
            if (taskInfo == null) {
                return Flux.error(new AgentException(AgentErrorCode.CONCURRENT_EXECUTION,
                        "该会话正在执行中，请稍后再试: " + conversationId));
            }
        }

        // AgentStart + AFTER_START providers
        AgentExecutionContext execCtx = new AgentExecutionContext(query, params);
        sink.tryEmitNext(new AgentStreamEvent.AgentStart());
        if (!stageManager.isEmpty()) {
            stageManager.afterStart(execCtx.toStageContext(), sink::tryEmitNext);
        }

        AtomicLong roundCounter = new AtomicLong(0);
        Disposable disposable = scheduleRound(messages, sink, roundCounter, params, execCtx, query);

        if (taskManager != null && conversationId != null && disposable != null) {
            taskManager.setDisposable(conversationId, disposable);
        }

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
        List<Message> messages = new ArrayList<>(state.getMessages());

        // 注入暂停工具的 ToolResponseMessage
        for (PendingToolCall ptc : state.getPendingToolCalls()) {
            AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                    ptc.id(), "function", ptc.name(), ptc.arguments());
            String result = toolCallExecutor.resolveResumeToolResult(ptc, toolCall, toolResults, state.getParams());
            toolCallExecutor.addNormalToolMessage(toolCall, result, messages);
        }

        String conversationId = state.getParams() != null ? state.getParams().getConversationId() : null;
        Sinks.Many<AgentStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();

        String query = state.getQuery();
        AtomicLong roundCounter = new AtomicLong(state.getCurrentRound() + 1);
        AgentExecutionContext execCtx = new AgentExecutionContext(query, state.getParams());
        Disposable disposable = scheduleRound(messages, sink, roundCounter, state.getParams(), execCtx, query);

        if (taskManager != null && conversationId != null && disposable != null) {
            taskManager.setDisposable(conversationId, disposable);
        }

        return wrapStreamFlux(sink, conversationId, roundCounter, state.getParams(), query);
    }

    /**
     * 为流式 Flux 统一添加 buffer 收集、完成、取消、错误处理。
     */
    private Flux<AgentStreamEvent> wrapStreamFlux(Sinks.Many<AgentStreamEvent> sink,
                                                  String conversationId,
                                                  AtomicLong roundCounter,
                                                  RunnableParams params,
                                                  String query) {
        StringBuilder textBuffer = new StringBuilder();
        StringBuilder thinkingBuffer = new StringBuilder();

        return sink.asFlux()
                .doOnNext(event -> {
                    if (event instanceof AgentStreamEvent.Text t) {
                        textBuffer.append(t.content());
                    } else if (event instanceof AgentStreamEvent.Thinking t) {
                        thinkingBuffer.append(t.content());
                    }
                })
                .doOnError(err -> handleStreamError(conversationId, err))
                .doFinally(signal -> {
                    log.info("Stream terminated: conversationId={}, signal={}, textLength={}, thinkingLength={}",
                            conversationId, signal, textBuffer.length(), thinkingBuffer.length());
                    if (signal != SignalType.ON_ERROR) {
                        handleStreamComplete(conversationId, roundCounter.get(), params, query,
                                textBuffer.toString(), thinkingBuffer.toString());
                    }
                });
    }

    private void handleStreamCancel(String conversationId) {
        log.info("\n\n Stream cancelled: conversationId={}", conversationId);
        if (taskManager != null && conversationId != null) {
            taskManager.removeTask(conversationId);
        }
    }

    private void handleStreamComplete(String conversationId, long round,
                                      RunnableParams params, String query, String answer, String think) {
        log.info("Stream completed: conversationId={}, round={}", conversationId, round);

        // 自动写入会话历史（answer 正文 + think 思考过程）
        if (conversationId != null) {
            messageBuilder.saveToChatMemory(query, answer, think, conversationId, params != null ? params.getUserId() : null);
        }

        // 长期记忆提取 + 语义记忆保存（MemoryPersistor 内部 stripThinkTags）
        memoryPersistor.persist(params, query, answer, conversationId);

        if (taskManager != null && conversationId != null) {
            taskManager.removeTask(conversationId);
        }
    }

    private void handleStreamError(String conversationId, Throwable err) {
        log.error("\n\n Stream error: conversationId={}", conversationId, err);
        if (taskManager != null && conversationId != null) {
            taskManager.removeTask(conversationId);
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

        RoundState roundState = new RoundState();

        // 上下文压缩（每轮 LLM 调用前执行）
        if (contextCompactor != null) {
            contextCompactor.compact(messages, query);
        }

        return llmInvoker.buildRoundChatClient().prompt()
                .messages(messages)
                .stream()
                .chatResponse()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> processChunk(chunk, sink, roundState))
                .doOnComplete(() -> finishRound(messages, sink, roundState, roundCounter, params, execCtx, query))
                .onErrorResume(err -> llmInvoker.handleStreamError(err, retryAttempt, sink,
                        () -> scheduleRound(messages, sink, roundCounter, params, execCtx, query, retryAttempt + 1),
                        "LLM stream error"))
                .subscribe();
    }

    private void processChunk(ChatResponse chunk, Sinks.Many<AgentStreamEvent> sink, RoundState state) {
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
        if (thinkingMode == ThinkingMode.REASONING_CONTENT) {
            String reasoning = thinkingModeProcessor.extractReasoningContent(chunk.getResult().getOutput());
            thinkingModeProcessor.processReasoningChunk(reasoning, state, sink);
        }
        thinkingModeProcessor.processStreamChunk(text, state, sink);
    }

    private void finishRound(List<Message> messages, Sinks.Many<AgentStreamEvent> sink,
                             RoundState state, AtomicLong roundCounter, RunnableParams params,
                             AgentExecutionContext execCtx, String query) {
        String conversationId = params != null ? params.getConversationId() : null;

        if (state.promptTokens >= 0 || state.finishReason != null) {
            log.info("LLM response detail: conversationId={}, promptTokens={}, completionTokens={}, finishReason={}",
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

            // BEFORE_COMPLETE providers + Complete 事件
            if (execCtx != null) {
                execCtx.setAnswer(state.textBuffer.toString());
                if (!stageManager.isEmpty()) {
                    stageManager.beforeComplete(execCtx.toStageContext(), sink::tryEmitNext);
                }
            }
            EmitResult completeResult = sink.tryEmitNext(new AgentStreamEvent.Complete());
            log.debug("tryEmitNext(Complete) result={}, conversationId={}", completeResult, conversationId);
            EmitResult emitResult = sink.tryEmitComplete();
            log.warn("tryEmitComplete result={}, conversationId={}", emitResult, conversationId);
            return;
        }

        // tool call 路径：构建 AssistantMessage 时携带 reasoningContent（通过 properties 传递）
        Map<String, Object> props = thinkingModeProcessor.buildReasoningProperties(state);
        AssistantMessage assistantMsg = AssistantMessage.builder()
                .content(state.textBuffer.isEmpty() ? "" : state.textBuffer.toString())
                .toolCalls(state.toolCalls)
                .properties(props)
                .build();
        messages.add(assistantMsg);

        if (maxRounds > 0 && roundCounter.get() >= maxRounds) {
            log.debug("Max rounds reached, forcing final answer: conversationId={}", conversationId);
            forceFinalStream(messages, sink, params, execCtx);
            return;
        }

        // === 流式暂停检查 ===
        // 流式路径中，PauseAdvisor 不会在 context 中标记（streaming advisor 不同）
        // 直接检查 PauseAdvisor 是否配置了拦截
        PauseCheckResult pauseCheck = checkStreamPause(state.toolCalls, messages, params,
                (int) roundCounter.get(), query);
        if (pauseCheck.isPaused()) {
            sink.tryEmitNext(new AgentStreamEvent.Paused(pauseCheck.state()));
            sink.tryEmitNext(new AgentStreamEvent.Complete());
            sink.tryEmitComplete();
            return;
        }

        // 发射 ToolStart 事件
        for (AssistantMessage.ToolCall tc : state.toolCalls) {
            sink.tryEmitNext(new AgentStreamEvent.ToolStart(tc.name(), tc.id(), tc.arguments()));
        }

        toolCallExecutor.executeToolCallsAsync(sink, state.toolCalls, messages, params, execCtx, () -> {
            scheduleRound(messages, sink, roundCounter, params, execCtx, query);
        });
    }

    /**
     * 流式路径的暂停检查。
     * 通过检查 ChatClient 的 Advisor 链中是否有 PauseAdvisor，
     * 判断当前 toolCalls 中是否有需要拦截的工具。
     */
    private PauseCheckResult checkStreamPause(List<AssistantMessage.ToolCall> toolCalls,
                                              List<Message> messages,
                                              RunnableParams params,
                                              int round,
                                              String query) {
        if (pauseAdvisor == null) {
            return PauseCheckResult.notPaused();
        }

        List<PendingToolCall> pending = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : toolCalls) {
            if (pauseAdvisor.shouldIntercept(tc.name())) {
                pending.add(new PendingToolCall(tc.id(), tc.name(), tc.arguments()));
            }
        }

        if (pending.isEmpty()) {
            return PauseCheckResult.notPaused();
        }

        // 执行非拦截工具
        toolCallExecutor.executeNonPendingTools(toolCalls, pending, messages, params);

        PauseState pauseState = PauseState.builder()
                .messages(List.copyOf(messages))
                .currentRound(round)
                .pendingToolCalls(pending)
                .params(params)
                .query(query)
                .build();

        log.debug("Stream paused at round {}, pending tools: {}", round, pending.size());
        return PauseCheckResult.paused(pauseState);
    }

    /**
     * 暂停检查结果。
     */
    private record PauseCheckResult(boolean paused, PauseState state) {
        static PauseCheckResult notPaused() {
            return new PauseCheckResult(false, null);
        }

        static PauseCheckResult paused(PauseState state) {
            return new PauseCheckResult(true, state);
        }

        boolean isPaused() {
            return paused;
        }
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

    private ChatResponse forceFinalAnswer(List<Message> messages, RunnableParams params) {
        List<Message> newMessages = new ArrayList<>(messages);

        newMessages.add(new SystemMessage(PromptConstants.MAX_ROUNDS_REACHED));

        ChatClientResponse ccResponse = llmInvoker.callLlm(newMessages);

        return ccResponse.chatResponse();
    }

    private void forceFinalStream(List<Message> messages, Sinks.Many<AgentStreamEvent> sink,
                                  RunnableParams params, AgentExecutionContext execCtx) {
        forceFinalStream(messages, sink, params, execCtx, 0);
    }

    private void forceFinalStream(List<Message> messages, Sinks.Many<AgentStreamEvent> sink,
                                  RunnableParams params, AgentExecutionContext execCtx, int retryAttempt) {
        List<Message> newMessages = new ArrayList<>(messages);

        newMessages.add(new SystemMessage(PromptConstants.MAX_ROUNDS_REACHED));

        StringBuilder answerBuffer = new StringBuilder();
        boolean[] inThink = {false};

        llmInvoker.buildRoundChatClient().prompt()
                .messages(newMessages)
                .stream()
                .chatResponse()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> {
                    String text = chunk.getResult().getOutput().getText();

                    if (thinkingMode == ThinkingMode.REASONING_CONTENT) {
                        String reasoning = thinkingModeProcessor.extractReasoningContent(chunk.getResult().getOutput());
                        if (reasoning != null && !reasoning.isEmpty()) {
                            sink.tryEmitNext(new AgentStreamEvent.Thinking(reasoning));
                        }
                    }
                    thinkingModeProcessor.processForceFinalChunk(text, inThink, answerBuffer, sink);
                })
                .doOnComplete(() -> {
                    // BEFORE_COMPLETE providers + Complete
                    if (execCtx != null) {
                        execCtx.setAnswer(answerBuffer.toString());
                        if (!stageManager.isEmpty()) {
                            stageManager.beforeComplete(execCtx.toStageContext(), sink::tryEmitNext);
                        }
                    }
                    sink.tryEmitNext(new AgentStreamEvent.Complete());
                    sink.tryEmitComplete();
                })
                .onErrorResume(err -> llmInvoker.handleStreamError(err, retryAttempt, sink,
                        () -> forceFinalStream(messages, sink, params, execCtx, retryAttempt + 1),
                        "forceFinal stream error"))
                .subscribe();
    }

}
