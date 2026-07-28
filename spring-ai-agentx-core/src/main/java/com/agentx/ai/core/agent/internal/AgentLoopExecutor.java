package com.agentx.ai.core.agent.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.agentx.ai.core.stage.AgentRuntimeContext;
import com.agentx.ai.core.stage.StageOutputManager;
import com.agentx.ai.core.stage.ThinkTagParser;
import com.agentx.ai.core.utils.JsonRepairUtil;

import com.agentx.ai.core.context.ContextCompactor;
import com.agentx.ai.core.memory.LongTermMemoryManager;
import com.agentx.ai.core.memory.store.ConversationStore;
import com.agentx.ai.core.memory.store.SessionMessageStore;
import com.agentx.ai.core.tools.toolsearch.DeferredToolRegistry;
import com.agentx.ai.core.memory.util.MemoryInjector;
import com.agentx.ai.core.memory.util.MemoryPersistor;
import com.agentx.ai.core.trace.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks.EmitResult;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent ReAct 循环执行器：多轮迭代调用 LLM、执行工具，直到产出最终答案或达到上限。
 * 要求 ChatClient 配置 internalToolExecutionEnabled(false)，工具调用由本类控制。
 *
 * @author bigchui
 */
public class AgentLoopExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopExecutor.class);

    /**
     * 最大循环轮次。
     */
    private final int maxRounds;
    private final Map<String, ToolCallback> toolMap;
    private final String askUserToolName;
    private final AgentTaskManager taskManager;
    private final MemoryInjector memoryInjector;
    private final LoopMessageBuilder messageBuilder;
    private final StageOutputManager stageManager;
    private final ThinkingMode thinkingMode;
    private final ThinkingModeProcessor thinkingModeProcessor;
    private final ToolCallExecutor toolCallExecutor;
    private final LlmInvoker llmInvoker;
    private final ContextCompactor contextCompactor;
    /**
     * 会话/Trace/暂停状态的持久化入口，集中所有落库副作用。
     */
    private final SessionPersister sessionPersister;

    /**
     * 每次执行（stream/call）独立创建，跟踪当前轮阶段以支持用户主动中断。
     */
    private InterruptContext interruptContext;

    private AgentLoopExecutor(Builder builder) {
        this.maxRounds = builder.maxRounds;
        this.askUserToolName = builder.askUserToolName;
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
        this.memoryInjector = new MemoryInjector(builder.longTermMemoryManager);

        // 记忆持久化器（委托给 SessionPersister 使用）
        MemoryPersistor memoryPersistor = new MemoryPersistor(builder.longTermMemoryManager);

        // 会话/Trace/暂停状态 统一持久化入口
        this.sessionPersister = new SessionPersister(
                builder.enableSession, builder.enableTrace,
                builder.conversationStore, builder.sessionMessageStore,
                builder.traceStore, builder.stateStore, memoryPersistor);

        // 消息构建器
        boolean hasTodoWrite = map.containsKey("TodoWrite");
        this.messageBuilder = new LoopMessageBuilder(
                builder.instructions, builder.sessionMessageStore,
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
        private SessionMessageStore sessionMessageStore;
        private ConversationStore conversationStore;
        private String instructions;
        private ChatModel chatModel;
        private LongTermMemoryManager longTermMemoryManager;
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

        public Builder sessionMessageStore(SessionMessageStore v) {
            this.sessionMessageStore = v;
            return this;
        }

        public Builder conversationStore(ConversationStore v) {
            this.conversationStore = v;
            return this;
        }

        public Builder instructions(String v) {
            this.instructions = v;
            return this;
        }

        public Builder longTermMemoryManager(LongTermMemoryManager v) {
            this.longTermMemoryManager = v;
            return this;
        }

        public Builder chatModel(ChatModel v) {
            this.chatModel = v;
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
     * 非流式执行 ReAct 循环。REASONING_CONTENT 模式下内部走流式收集思考内容。
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
     * 是否结构化输出（outputType != null）。
     */
    private boolean isStructuredOutput(RunnableParams params) {
        return params != null && params.getOutputType() != null;
    }

    /**
     * REASONING_CONTENT 模式内部走流式收集，blockLast 后同步落库避免 doFinally 时序问题。
     */
    private AgentResult callViaStreamForResult(String query, RunnableParams params) {
        BuiltMessages built = messageBuilder.buildInitialMessages(query, params);
        List<Message> messages = built.messages();
        Sinks.Many<AgentStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        AgentRuntimeContext execCtx = new AgentRuntimeContext(query, params);
        execCtx.setNewMsgStartIndex(built.newMsgStartIndex());
        execCtx.setOriginalMessagesSnapshot(new ArrayList<>(messages));
        sessionPersister.initSession(execCtx, params, query);
        AtomicLong roundCounter = new AtomicLong(0);

        registerInterruptContext(messages, sink, params, query, execCtx, roundCounter);

        return blockForResult(messages, sink, roundCounter, params, execCtx, query);
    }

    /**
     * sink 阻塞收集：scheduleRound → doOnNext 累积 → blockLast → AgentResult，供 call/resume 共用。
     */
    private AgentResult blockForResult(List<Message> messages,
                                       Sinks.Many<AgentStreamEvent> sink,
                                       AtomicLong roundCounter,
                                       RunnableParams params,
                                       AgentRuntimeContext execCtx,
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
                        // 工具调用轮清空 answer（最终答案只取最后一轮），结构化输出时同步清空 think
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
            execCtx.markTerminal("error");
            sessionPersister.persistOnTerminal(execCtx, messages, SignalType.ON_ERROR);
            return new AgentResult.Failed(errorHolder[0], AgentErrorCode.LLM_CALL_FAILED);
        }
        if (pauseHolder[0] != null) {
            execCtx.markTerminal("interrupted");
            sessionPersister.persistOnTerminal(execCtx, messages, SignalType.CANCEL);
            return new AgentResult.Paused(pauseHolder[0]);
        }

        String finalAnswer = answer.toString();
        if (params != null && params.getOutputType() != null) {
            finalAnswer = JsonRepairUtil.fixJson(finalAnswer);
        }

        execCtx.markTerminal("completed");
        sessionPersister.persistOnTerminal(execCtx, messages, SignalType.ON_COMPLETE);

        return new AgentResult.Completed(
                finalAnswer,
                think.length() > 0 ? think.toString() : null,
                stageOutputs);
    }

    /**
     * 从暂停状态恢复执行（非流式）。
     */
    public AgentResult resume(PauseState state, Map<String, String> toolResults) {
        List<Message> messages = buildResumeMessages(state, toolResults, null);

        String conversationId = state.getParams() != null ? state.getParams().getConversationId() : null;
        if (taskManager != null && conversationId != null) {
            if (taskManager.registerTask(conversationId, null) == null) {
                return new AgentResult.Failed("该会话正在执行中，请稍后再试: " + conversationId,
                        AgentErrorCode.CONCURRENT_EXECUTION);
            }
        }
        try {
            AgentResult result = resumeViaStreamForResult(messages, state, null);
            if (result instanceof AgentResult.Completed) {
                sessionPersister.deletePauseState(conversationId);
            }
            return result;
        } finally {
            if (taskManager != null && conversationId != null) {
                taskManager.removeTask(conversationId);
            }
        }
    }

    /**
     * 流式-backed 恢复执行，委托 blockForResult。
     */
    private AgentResult resumeViaStreamForResult(List<Message> messages, PauseState state,
                                                 @Nullable String resumeQuery) {
        Sinks.Many<AgentStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicLong roundCounter = new AtomicLong(state.getCurrentRound());
        AgentRuntimeContext execCtx = new AgentRuntimeContext(state.getQuery(), state.getParams());
        // 暂停时已落库 [query .. assistant(tool_calls)]，本次恢复只持久化新增的 tool 响应及后续消息
        execCtx.setNewMsgStartIndex(state.getMessages().size());
        execCtx.setOriginalMessagesSnapshot(new ArrayList<>(messages));
        String effectiveQuery = sessionPersister.initSessionFromResume(execCtx, state, resumeQuery);

        registerInterruptContext(messages, sink, state.getParams(), effectiveQuery, execCtx, roundCounter);

        return blockForResult(messages, sink, roundCounter, state.getParams(), execCtx, effectiveQuery);
    }

    /**
     * 按暂停原因分发工具结果解析：USER_INTERRUPT+TOOL_EXECUTION 走占位/重跑，其余走默认。
     */
    private String resolveToolResult(PendingToolCall ptc,
                                     AssistantMessage.ToolCall toolCall,
                                     Map<String, String> toolResults,
                                     PauseState state,
                                     @Nullable String resumeQuery) {
        if (state.getReason() == PauseReason.USER_INTERRUPT
                && state.getSafePoint() == SafePoint.TOOL_EXECUTION) {
            // 用户中断后带着新消息恢复 → 不重跑工具，注入占位让 LLM 根据新指令决策
            if (resumeQuery != null && !resumeQuery.isBlank()) {
                return PromptConstants.buildInterruptToolSkippedMessage(ptc.name(), ptc.arguments());
            }
            return toolCallExecutor.resolveInterruptToolResult(ptc, toolCall, state.getParams());
        }
        return toolCallExecutor.resolveResumeToolResult(ptc, toolCall, toolResults, state.getParams());
    }

    /**
     * 从 PauseState 重建消息列表，注入 pending tool calls 的 ToolResponseMessage。
     */
    private List<Message> buildResumeMessages(PauseState state, Map<String, String> toolResults,
                                              @Nullable String resumeQuery) {
        List<Message> messages = new ArrayList<>(state.getMessages());
        for (PendingToolCall ptc : state.getPendingToolCalls()) {
            AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                    ptc.id(), "function", ptc.name(), ptc.arguments());
            String result = resolveToolResult(ptc, toolCall, toolResults, state, resumeQuery);
            toolCallExecutor.addNormalToolMessage(toolCall, result, messages);
        }
        if (resumeQuery != null && !resumeQuery.isBlank()) {
            messages.add(new UserMessage(
                    PromptConstants.buildResumeInterruptMessage(state.getQuery(), resumeQuery)));
        }
        return messages;
    }

    /**
     * 流式执行 ReAct 循环，返回 AgentStreamEvent 流。
     */
    public Flux<AgentStreamEvent> stream(String query, RunnableParams params) {
        String conversationId = params != null ? params.getConversationId() : null;
        BuiltMessages built = messageBuilder.buildInitialMessages(query, params);
        List<Message> messages = built.messages();

        Sinks.Many<AgentStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();

        if (taskManager != null && conversationId != null) {
            AgentTaskManager.TaskInfo taskInfo = taskManager.registerTask(conversationId, sink);
            if (taskInfo == null) {
                return Flux.error(new AgentException(AgentErrorCode.CONCURRENT_EXECUTION,
                        "该会话正在执行中，请稍后再试: " + conversationId));
            }
        }

        AgentRuntimeContext execCtx = new AgentRuntimeContext(query, params);
        execCtx.setNewMsgStartIndex(built.newMsgStartIndex());
        execCtx.setOriginalMessagesSnapshot(new ArrayList<>(messages));
        sessionPersister.initSession(execCtx, params, query);
        sink.tryEmitNext(new AgentStreamEvent.AgentStart());
        if (!stageManager.isEmpty()) {
            stageManager.afterStart(execCtx.toStageContext(), sink::tryEmitNext);
        }

        AtomicLong roundCounter = new AtomicLong(0);

        registerInterruptContext(messages, sink, params, query, execCtx, roundCounter);

        scheduleRound(messages, sink, roundCounter, params, execCtx, query);

        return wrapStreamFlux(sink, conversationId, messages, execCtx);
    }

    /**
     * 从暂停状态恢复流式执行。
     */
    public Flux<AgentStreamEvent> resumeStream(PauseState state, Map<String, String> toolResults) {
        return resumeStream(state, toolResults, null);
    }

    /**
     * 从暂停状态恢复流式执行，可附带用户中断后补充的新消息。
     */
    public Flux<AgentStreamEvent> resumeStream(PauseState state, Map<String, String> toolResults,
                                               @Nullable String resumeQuery) {
        List<Message> messages = buildResumeMessages(state, toolResults, resumeQuery);

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

        AtomicLong roundCounter = new AtomicLong(state.getCurrentRound());
        AgentRuntimeContext execCtx = new AgentRuntimeContext(state.getQuery(), state.getParams());
        // 暂停时已落库 [query .. assistant(tool_calls)]，本次恢复只持久化新增的 tool 响应及后续消息
        execCtx.setNewMsgStartIndex(state.getMessages().size());
        execCtx.setOriginalMessagesSnapshot(new ArrayList<>(messages));
        String effectiveQuery = sessionPersister.initSessionFromResume(execCtx, state, resumeQuery);

        // 发射 ResumeStart 事件，通知前端"恢复执行开始"
        sink.tryEmitNext(new AgentStreamEvent.ResumeStart(
                conversationId,
                state.getCurrentRound(),
                state.getInterruptedAt(),
                state.getReason()));

        registerInterruptContext(messages, sink, state.getParams(), effectiveQuery, execCtx, roundCounter);

        // disposable 的注册由 scheduleRound 内部每轮刷新负责，此处不再手动 setDisposable
        scheduleRound(messages, sink, roundCounter, state.getParams(), execCtx, effectiveQuery);

        // 正常完成时清理快照（Paused 不能删，会通过 UPSERT 覆盖）
        var wasPaused = new java.util.concurrent.atomic.AtomicBoolean(false);
        return wrapStreamFlux(sink, conversationId, messages, execCtx)
                .doOnNext(event -> {
                    if (event instanceof AgentStreamEvent.Paused) {
                        wasPaused.set(true);
                    }
                })
                .doOnComplete(() -> {
                    if (!wasPaused.get()) {
                        sessionPersister.deletePauseState(conversationId);
                    }
                });
    }

    /**
     * 流式 Flux 统一加错误处理、终态落库兜底和任务清理。
     */
    private Flux<AgentStreamEvent> wrapStreamFlux(Sinks.Many<AgentStreamEvent> sink,
                                                  String conversationId,
                                                  List<Message> messages,
                                                  AgentRuntimeContext execCtx) {
        return sink.asFlux()
                .doOnError(err -> handleStreamError(conversationId, err))
                .doFinally(signal -> {
                    log.debug("Stream terminated: conversationId={}, signal={}", conversationId, signal);
                    // 异常终止兜底：不信任 Reactor signal，统一按 CANCEL 处理（自然完成已显式落库）
                    SignalType fallbackSignal = signal == SignalType.ON_ERROR ? SignalType.ON_ERROR : SignalType.CANCEL;
                    sessionPersister.persistOnTerminal(execCtx, messages, fallbackSignal);
                    if (taskManager != null && conversationId != null) {
                        taskManager.stopTask(conversationId);
                    }
                });
    }

    private void handleStreamError(String conversationId, Throwable err) {
        log.error("\n\n Stream error: conversationId={}", conversationId, err);
    }

    /**
     * 注册中断上下文和处理器，供 stream / resumeStream / callViaStreamForResult / resumeViaStreamForResult 共用。
     */
    private void registerInterruptContext(List<Message> messages, Sinks.Many<AgentStreamEvent> sink,
                                          RunnableParams params, String query,
                                          AgentRuntimeContext execCtx, AtomicLong roundCounter) {
        String conversationId = params != null ? params.getConversationId() : null;
        this.interruptContext = new InterruptContext(messages, sink, params, query);
        if (taskManager != null && conversationId != null) {
            taskManager.setInterruptHandler(conversationId, msg -> {
                PauseState snapshot = interruptContext.buildSnapshot(msg, execCtx, roundCounter.get());
                sessionPersister.persistPauseState(snapshot);
                // 标记中断态并显式落库后再发射 Paused + complete sink，避免 doFinally 时序竞态
                execCtx.markTerminal("interrupted");
                sessionPersister.persistOnTerminal(execCtx, messages, SignalType.CANCEL);
                interruptContext.emitPausedAndComplete(snapshot);
            });
        }
    }

    private Disposable scheduleRound(List<Message> messages, Sinks.Many<AgentStreamEvent> sink,
                                     AtomicLong roundCounter, RunnableParams params,
                                     AgentRuntimeContext execCtx, String query) {
        return scheduleRound(messages, sink, roundCounter, params, execCtx, query, 0);
    }

    private Disposable scheduleRound(List<Message> messages, Sinks.Many<AgentStreamEvent> sink,
                                     AtomicLong roundCounter, RunnableParams params,
                                     AgentRuntimeContext execCtx, String query, int retryAttempt) {
        long round = roundCounter.incrementAndGet();
        String conversationId = params != null ? params.getConversationId() : null;
        log.debug("Scheduling round: {}, conversationId={}, retryAttempt={}", round, conversationId, retryAttempt);

        // 进入 LLM_STREAMING 阶段，可被中断
        if (interruptContext != null) {
            interruptContext.enterLlmStreaming();
        }

        RoundState roundState = new RoundState();
        // 注册当前轮缓冲区，使中断回调能读取部分输出并持久化
        if (interruptContext != null) {
            interruptContext.setRoundBuffers(roundState.textBuffer, roundState.reasoningBuffer);
        }
        long startTime = System.currentTimeMillis();

        // 上下文压缩（每轮 LLM 调用前执行）
        if (contextCompactor != null) {
            contextCompactor.compact(messages, query, execCtx.getConversationId(), execCtx.getSessionId());
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
                            () -> scheduleRound(messages, sink, roundCounter, params, execCtx, query, retryAttempt + 1),
                            "LLM stream error",
                            () -> {
                                execCtx.markTerminal("error");
                                sessionPersister.persistOnTerminal(execCtx, messages, SignalType.ON_ERROR);
                            });
                    return Flux.empty();
                })
                .subscribe();

        // 每轮刷新 taskManager 的 disposable，保证 stopTask 能中断当前在飞的轮次
        if (taskManager != null && conversationId != null) {
            taskManager.setDisposable(conversationId, disposable);
        }
        return disposable;
    }

    private void processChunk(ChatResponse chunk, Sinks.Many<AgentStreamEvent> sink,
                              RoundState state, AgentRuntimeContext execCtx) {
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
                             AgentRuntimeContext execCtx, String query, long durationMs) {
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
                AssistantMessage finalAssistant = AssistantMessage.builder()
                        .content(state.textBuffer.toString())
                        .properties(props)
                        .build();
                messages.add(finalAssistant);
                execCtx.appendOriginalMessage(finalAssistant);
            }

            // 记录 trace（最终答案轮，trace 保持单轮 think）
            String roundThink = state.reasoningBuffer.length() > 0 ? state.reasoningBuffer.toString() : null;
            sessionPersister.recordTrace(execCtx, round, requestJson, state.textBuffer.toString(),
                    roundThink, state.promptTokens, state.completionTokens, durationMs);

            // BEFORE_COMPLETE providers + Complete 事件
            if (!stageManager.isEmpty()) {
                stageManager.beforeComplete(execCtx.toStageContext(), sink::tryEmitNext);
            }
            // 标记终态：显式落库后再 complete sink，避免 doFinally 时序竞态导致 blockLast 提前返回
            execCtx.markTerminal("completed");
            sessionPersister.persistOnTerminal(execCtx, messages, SignalType.ON_COMPLETE);
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
        execCtx.appendOriginalMessage(assistantMsg);

        if (maxRounds > 0 && roundCounter.get() >= maxRounds) {
            log.debug("Max rounds reached, forcing final answer: conversationId={}", conversationId);
            // 记录 trace（工具调用轮，达到上限）
            sessionPersister.recordTrace(execCtx, round, requestJson, sessionPersister.serializeToolCalls(safeToolCalls), null,
                    state.promptTokens, state.completionTokens, durationMs);
            forceFinalStream(messages, sink, params, execCtx, query);
            return;
        }

        // === 流式暂停检查（通过 Advisor chain context） ===
        if (state.advisorContext != null
                && Boolean.TRUE.equals(state.advisorContext.get(PauseAdvisor.PAUSE_REQUIRED))) {
            List<PendingToolCall> pending = PauseAdvisor.getPendingTools(state.advisorContext);

            // 执行非拦截工具
            toolCallExecutor.executeNonPendingTools(safeToolCalls, pending, messages, params, execCtx);

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

            sessionPersister.persistPauseState(pauseState);

            // 记录 trace（暂停前）
            sessionPersister.recordTrace(execCtx, round, requestJson, sessionPersister.serializeToolCalls(safeToolCalls), null,
                    state.promptTokens, state.completionTokens, durationMs);

            log.debug("Stream paused at round {}, pending tools: {}", roundCounter.get(), pending.size());
            // 标记中断态并显式落库后再 complete sink，避免 doFinally 时序竞态
            execCtx.markTerminal("interrupted");
            sessionPersister.persistOnTerminal(execCtx, messages, SignalType.CANCEL);
            sink.tryEmitNext(new AgentStreamEvent.Paused(pauseState));
            sink.tryEmitComplete();
            return;
        }

        // 记录 trace（工具调用轮）
        sessionPersister.recordTrace(execCtx, round, requestJson, sessionPersister.serializeToolCalls(safeToolCalls), null,
                state.promptTokens, state.completionTokens, durationMs);

        // 发射 ToolStart 事件
        for (AssistantMessage.ToolCall tc : safeToolCalls) {
            sink.tryEmitNext(new AgentStreamEvent.ToolStart(tc.name(), tc.id(), tc.arguments()));
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
                                  RunnableParams params, AgentRuntimeContext execCtx, String query) {
        forceFinalStream(messages, sink, params, execCtx, query, 0);
    }

    private void forceFinalStream(List<Message> messages, Sinks.Many<AgentStreamEvent> sink,
                                  RunnableParams params, AgentRuntimeContext execCtx, String query, int retryAttempt) {
        // 闭合最后一轮未执行的 tool_calls（直接追加到原 messages，使终态落库能完整记录）
        if (!messages.isEmpty() && messages.get(messages.size() - 1) instanceof AssistantMessage lastMsg) {
            if (lastMsg.getToolCalls() != null && !lastMsg.getToolCalls().isEmpty()) {
                for (AssistantMessage.ToolCall tc : lastMsg.getToolCalls()) {
                    toolCallExecutor.addNormalToolMessage(tc, "Agent maximum rounds reached. Tool execution skipped.", messages);
                }
            }
        }

        StringBuilder answerBuffer = new StringBuilder();
        StringBuilder reasoningBuffer = new StringBuilder();
        boolean[] inThink = {false};
        final long[] finalUsage = {0, 0};

        Disposable disposable = llmInvoker.buildRoundChatClient().prompt()
                .messages(messages)
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
                            reasoningBuffer.append(reasoning);
                            sink.tryEmitNext(new AgentStreamEvent.Thinking(reasoning));
                        }
                    }
                    thinkingModeProcessor.processForceFinalChunk(text, inThink, answerBuffer, reasoningBuffer, sink);
                })
                .doOnComplete(() -> {
                    String conversationId = params != null ? params.getConversationId() : null;
                    execCtx.accumulateTokens(finalUsage[0], finalUsage[1]);
                    // 将最终答案作为 AssistantMessage 追加到 messages，供终态落库捕获
                    if (!answerBuffer.isEmpty() || !reasoningBuffer.isEmpty()) {
                        Map<String, Object> props = Map.of("reasoningContent", reasoningBuffer.toString());
                        AssistantMessage finalAssistant = AssistantMessage.builder()
                                .content(answerBuffer.toString())
                                .properties(props)
                                .build();
                        messages.add(finalAssistant);
                        execCtx.appendOriginalMessage(finalAssistant);
                    }
                    if (!stageManager.isEmpty()) {
                        stageManager.beforeComplete(execCtx.toStageContext(), sink::tryEmitNext);
                    }
                    // 标记终态：显式落库后再 complete sink，避免 doFinally 时序竞态
                    execCtx.markTerminal("completed");
                    sessionPersister.persistOnTerminal(execCtx, messages, SignalType.ON_COMPLETE);
                    sink.tryEmitNext(new AgentStreamEvent.Complete(
                            execCtx.getTotalPromptTokens(), execCtx.getTotalCompletionTokens(),
                            conversationId, execCtx.getSessionId(), null));
                    sink.tryEmitComplete();
                })
                .onErrorResume(err -> llmInvoker.handleStreamError(err, retryAttempt, sink,
                        () -> forceFinalStream(messages, sink, params, execCtx, query, retryAttempt + 1),
                        "forceFinal stream error",
                        () -> {
                            execCtx.markTerminal("error");
                            sessionPersister.persistOnTerminal(execCtx, messages, SignalType.ON_ERROR);
                        }))
                .subscribe();

        // 同 scheduleRound：每轮刷新 disposable，保证 stopTask 可中断当前在飞的 forceFinal 流。
        String conversationId = params != null ? params.getConversationId() : null;
        if (taskManager != null && conversationId != null) {
            taskManager.setDisposable(conversationId, disposable);
        }
    }

}
