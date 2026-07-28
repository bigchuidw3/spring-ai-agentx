package com.agentx.ai.core.agent;

import com.agentx.ai.core.context.ContextCompactor;
import com.agentx.ai.core.context.ContextPolicy;
import com.agentx.ai.core.context.compress.CompressionStrategy;
import com.agentx.ai.core.context.compress.LlmSummarizer;
import com.agentx.ai.core.context.compress.NoOpOffloadStore;
import com.agentx.ai.core.context.compress.OffloadStore;
import com.agentx.ai.core.context.compress.SessionBackedOffloadStore;
import com.agentx.ai.core.context.compress.l1.HistoricalToolListStrategy;
import com.agentx.ai.core.context.compress.l2.LargeMsgOffloadWithKeepStrategy;
import com.agentx.ai.core.context.compress.l3.LargeMsgOffloadNoKeepStrategy;
import com.agentx.ai.core.context.compress.l4.HistoricalRoundSummaryStrategy;
import com.agentx.ai.core.context.compress.l5.CurrentRoundLargeMsgStrategy;
import com.agentx.ai.core.context.compress.l6.CurrentRoundOverallStrategy;
import com.agentx.ai.core.advisors.PauseAdvisor;
import com.agentx.ai.core.advisors.RequestLoggingAdvisor;
import com.agentx.ai.core.agent.internal.AgentLoopExecutor;
import com.agentx.ai.core.agent.internal.AgentTaskManager;
import com.agentx.ai.core.interrupt.PauseStateStore;
import com.agentx.ai.core.interrupt.InMemoryPauseStateStore;
import com.agentx.ai.core.interrupt.PauseReason;
import com.agentx.ai.core.model.AgentResult;
import com.agentx.ai.core.model.AgentStreamEvent;
import com.agentx.ai.core.model.PauseState;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.model.StageOutputProvider;
import com.agentx.ai.core.model.StageTiming;
import com.agentx.ai.core.model.ThinkingMode;
import com.agentx.ai.core.memory.semantic.SemanticMemoryManager;
import com.agentx.ai.core.memory.store.DataSourceStorageFactory;
import com.agentx.ai.core.memory.store.MemoryStore;
import com.agentx.ai.core.memory.store.SemanticMemoryStore;
import com.agentx.ai.core.memory.store.SessionMessageStore;
import com.agentx.ai.core.memory.store.ConversationStore;
import com.agentx.ai.core.tools.AskUserTool;
import com.agentx.ai.core.tools.ContextReloadTool;
import com.agentx.ai.core.trace.TraceStore;
import com.agentx.ai.core.tools.SubAgentTool;
import com.agentx.ai.core.utils.ToolMergeUtil;
import com.agentx.ai.core.tools.toolsearch.DeferredToolRegistry;
import com.agentx.ai.core.tools.toolsearch.ToolSearchConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * ReactAgent - 基于 ReAct 范式的智能体实现。
 * <p>
 * 实现 Reasoning（推理）+ Acting（行动）循环模式，通过多轮对话完成复杂任务。
 * 支持自动多轮推理和工具调用、流式输出、会话记忆管理。
 * <p>
 * ChatClient 必须配置 internalToolExecutionEnabled(false) 以将工具执行权转交给框架。
 *
 * @author bigchui
 *
 */
public class ReactAgent {

    private static final Logger log = LoggerFactory.getLogger(ReactAgent.class);

    private final String name;
    private final String description;
    private final ChatClient chatClient;
    private final ChatModel chatModel;
    private final int maxRounds;
    private final List<ToolCallback> tools;
    private final List<Advisor> advisors;
    private final AgentTaskManager taskManager;
    private final String instructions;
    private final SessionMessageStore sessionMessageStore;
    private final ConversationStore conversationStore;
    private final MemoryStore memoryStore;
    private final SemanticMemoryManager semanticMemoryManager;
    private final DataSource dataSource;
    private final boolean enableProfileMemory;
    private boolean enableSession;
    private final List<StageOutputProvider> stageOutputProviders;
    private final ThinkingMode thinkingMode;
    private final int maxRetries;
    private final ContextPolicy contextPolicy;
    private final DeferredToolRegistry deferredToolRegistry;
    private TraceStore traceStore;
    private final boolean enableTrace;

    /**
     * Agent 暂停状态存储。默认内存实现，可通过 {@link Builder#stateStore(PauseStateStore)} 自定义。
     * <p>用于 {@link #interrupt(String)} 时自动持久化 PauseState，
     * 配合 {@link #hasInterruptedState(String)} / {@link #resumeStream(String)} 实现断点重连。
     */
    private final PauseStateStore stateStore;
    private final int maxHistoryRounds;
    private final int maxHistoryTokens;
    private final int sessionSummarizeStep;

    private ReactAgent(Builder builder, SessionMessageStore sessionMessageStore,
                       ConversationStore conversationStore, MemoryStore memoryStore,
                       SemanticMemoryManager semanticMemoryManager, TraceStore traceStore,
                       PauseStateStore stateStore) {
        this.deferredToolRegistry = builder.deferredToolRegistry;
        this.maxHistoryRounds = builder.maxHistoryRounds;
        this.maxHistoryTokens = builder.maxHistoryTokens;
        this.sessionSummarizeStep = builder.sessionSummarizeStep;

        // 构建 ChatClient，统一配置工具选项和 Advisors
        ChatClient.Builder clientBuilder = ChatClient.builder(builder.chatModel);

        // 添加 Advisors
        if (!builder.advisors.isEmpty()) {
            clientBuilder.defaultAdvisors(builder.advisors.toArray(new Advisor[0]));
        }

        // 配置工具选项 - 当有 deferredToolRegistry 时，ChatClient 只包含 alwaysLoad 工具
        List<ToolCallback> activeTools = builder.tools;
        var toolOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(activeTools.toArray(new ToolCallback[0]))
                .internalToolExecutionEnabled(false)
                .build();

        // 同时设置 defaultOptions 和 defaultToolCallbacks
        clientBuilder.defaultOptions(toolOptions);
        clientBuilder.defaultToolCallbacks(activeTools.toArray(new ToolCallback[0]));

        this.chatClient = clientBuilder.build();
        this.name = builder.name;
        this.description = builder.description;
        this.chatModel = builder.chatModel;
        this.maxRounds = builder.maxRounds;
        this.tools = List.copyOf(builder.tools);
        this.advisors = List.copyOf(builder.advisors);
        this.taskManager = builder.taskManager;
        this.instructions = builder.instructions;
        this.sessionMessageStore = sessionMessageStore;
        this.conversationStore = conversationStore;
        this.memoryStore = memoryStore;
        this.semanticMemoryManager = semanticMemoryManager;
        this.dataSource = builder.dataSource;
        this.enableProfileMemory = builder.enableProfileMemory;
        this.enableSession = builder.enableSession;
        this.stageOutputProviders = builder.stageOutputProviders;
        this.thinkingMode = builder.thinkingMode;
        this.maxRetries = builder.maxRetries;
        this.contextPolicy = builder.contextPolicy;
        this.traceStore = traceStore;
        this.enableTrace = builder.enableTrace;
        this.stateStore = stateStore;
    }

    /**
     * 创建 Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private AgentLoopExecutor createExecutor() {
        // 从 PauseAdvisor 中提取 askUserToolName，供 ToolCallExecutor 的 resume 逻辑使用
        String askUserToolName = null;
        for (Advisor advisor : advisors) {
            if (advisor instanceof PauseAdvisor pa) {
                if (pa.getAskUserToolName() != null) {
                    askUserToolName = pa.getAskUserToolName();
                }
            }
        }

        var executorBuilder = AgentLoopExecutor.builder()
                .chatClient(chatClient)
                .maxRounds(maxRounds)
                .tools(tools)
                .taskManager(taskManager)
                .sessionMessageStore(sessionMessageStore)
                .conversationStore(conversationStore)
                .instructions(instructions)
                .memoryStore(memoryStore)
                .chatModel(chatModel)
                .enableProfileMemory(enableProfileMemory)
                .enableSession(enableSession)
                .enableTrace(enableTrace)
                .askUserToolName(askUserToolName)
                .stageOutputProviders(stageOutputProviders)
                .thinkingMode(thinkingMode)
                .maxRetries(maxRetries)
                .advisors(advisors)
                .maxHistoryRounds(maxHistoryRounds)
                .maxHistoryTokens(maxHistoryTokens)
                .sessionSummarizeStep(sessionSummarizeStep);

        // 上下文压缩（可选）
        if (this.contextPolicy != null) {
            OffloadStore offloadStore = (enableSession && sessionMessageStore != null)
                    ? new SessionBackedOffloadStore(sessionMessageStore)
                    : new NoOpOffloadStore();
            LlmSummarizer summarizer = new LlmSummarizer(this.chatModel);
            List<CompressionStrategy> chain = new ArrayList<>();
            chain.add(new HistoricalToolListStrategy());
            chain.add(new LargeMsgOffloadWithKeepStrategy());
            chain.add(new LargeMsgOffloadNoKeepStrategy());
            chain.add(new HistoricalRoundSummaryStrategy(summarizer));
            chain.add(new CurrentRoundLargeMsgStrategy(summarizer));
            chain.add(new CurrentRoundOverallStrategy(summarizer));
            ContextCompactor compactor = new ContextCompactor(
                    this.contextPolicy, this.chatModel,
                    offloadStore, sessionMessageStore,
                    chain);
            executorBuilder.contextCompactor(compactor);

            // context_reload 工具（仅在启用 session 时注册，追加到用户已配置的 tools 之后）
            if (enableSession && sessionMessageStore != null) {
                ToolCallback[] reloadCallbacks = org.springframework.ai.support.ToolCallbacks.from(
                        new ContextReloadTool(sessionMessageStore));
                List<ToolCallback> merged = new ArrayList<>(tools);
                for (ToolCallback tc : reloadCallbacks) {
                    merged.add(tc);
                }
                executorBuilder.tools(merged);
            }
        }

        // 语义记忆（可选第三层）
        if (semanticMemoryManager != null) {
            executorBuilder.semanticMemoryManager(semanticMemoryManager);
        }

        // 延迟工具注册（可选）
        if (deferredToolRegistry != null) {
            executorBuilder.deferredToolRegistry(deferredToolRegistry);
        }

        // 审计 trace（可选）
        if (traceStore != null) {
            executorBuilder.traceStore(traceStore);
        }

        // 中断状态持久化（必有默认实现）
        if (stateStore != null) {
            executorBuilder.stateStore(stateStore);
        }

        return executorBuilder.build();
    }

    /**
     * 同步调用 Agent
     *
     * @param query 用户消息
     * @return Agent 响应文本
     */
    public String call(String query) {
        return call(query, RunnableParams.empty());
    }

    /**
     * 同步调用 Agent（带参数）
     *
     * @param query  用户消息
     * @param params 调用参数
     * @return Agent 响应文本
     */
    public String call(String query, RunnableParams params) {
        if (params == null) {
            params = RunnableParams.empty();
        }
        AgentResult result = createExecutor().call(query, params);
        return result.answer();
    }

    /**
     * 流式调用 Agent
     *
     * @param query 用户消息
     * @return 文本流
     */
    public Flux<String> stream(String query) {
        return stream(query, RunnableParams.empty());
    }

    /**
     * 流式调用 Agent（带参数）
     *
     * @param query  用户消息
     * @param params 调用参数
     * @return 文本流（过滤掉暂停事件）
     */
    public Flux<String> stream(String query, RunnableParams params) {
        if (params == null) {
            params = RunnableParams.empty();
        }
        return createExecutor().stream(query, params)
                .filter(e -> e instanceof AgentStreamEvent.Text)
                .map(e -> ((AgentStreamEvent.Text) e).content());
    }

    // ==================== AgentResult API (完整场景) ====================

    /**
     * 同步调用 Agent，返回完整结果（包含暂停状态）。
     *
     * @param query  用户消息
     * @param params 调用参数
     * @return AgentResult（Completed 或 Paused）
     */
    public AgentResult callForResult(String query, RunnableParams params) {
        if (params == null) {
            params = RunnableParams.empty();
        }
        return createExecutor().call(query, params);
    }

    /**
     * 流式调用 Agent，返回完整事件流（包含暂停事件）。
     *
     * @param query  用户消息
     * @param params 调用参数
     * @return AgentStreamEvent 流
     */
    public Flux<AgentStreamEvent> streamForResult(String query, RunnableParams params) {
        if (params == null) {
            params = RunnableParams.empty();
        }
        return createExecutor().stream(query, params);
    }

    // ==================== 恢复 API ====================

    /**
     * 从暂停状态恢复同步执行。
     *
     * @param state       暂停状态
     * @param toolResults 工具调用结果 (key=toolCallId, value=结果文本)
     * @return AgentResult（Completed 或 Paused）
     */
    public AgentResult resume(PauseState state, Map<String, String> toolResults) {
        return createExecutor().resume(state, toolResults);
    }

    /**
     * 从暂停状态恢复流式执行。
     *
     * @param state       暂停状态
     * @param toolResults 工具调用结果
     * @return AgentStreamEvent 流
     */
    public Flux<AgentStreamEvent> resumeStream(PauseState state, Map<String, String> toolResults) {
        return createExecutor().resumeStream(state, toolResults);
    }

    /**
     * 停止指定会话的流式任务
     *
     * @param conversationId 会话 ID
     * @return 是否成功停止
     */
    public boolean stopStream(String conversationId) {
        if (taskManager == null) {
            return false;
        }
        return taskManager.stopTask(conversationId);
    }

    // ==================== 用户主动中断与恢复 ====================

    /**
     * 用户主动中断当前会话的执行。
     * <p>
     * 触发已注册的中断回调：在 {@link PauseStateStore} 中持久化当前 PauseState，
     * 发射 {@link AgentStreamEvent.Paused} 事件（reason={@link PauseReason#USER_INTERRUPT}），
     * 然后停止 LLM 流式订阅。
     *
     * <p>工具执行阶段被中断时，框架无法取消已发出的外部调用（如 MCP / HTTP），
     * 副作用可能已部分发生。PauseState.interruptPhase 标识具体阶段，
     * 恢复时按工具幂等性策略重放 pendingToolCalls。
     *
     * @param conversationId 会话 ID
     * @param message        中断说明消息（可为 null，会附加到 PauseState.interruptMessage）
     * @return true 表示任务存在并已触发中断流程
     */
    public boolean interrupt(String conversationId, String message) {
        if (taskManager == null) {
            return false;
        }
        boolean triggered = taskManager.interrupt(conversationId, message);

        // interrupt 回调内已发射 Paused 事件；这里同步捕获 PauseState 并持久化
        // 实际的持久化由 AgentLoopExecutor 在中断回调里通过 storeState 桥接完成
        return triggered;
    }

    /**
     * 用户主动中断（无消息说明）。
     */
    public boolean interrupt(String conversationId) {
        return interrupt(conversationId, null);
    }

    /**
     * 检查指定会话是否存在未恢复的中断状态。
     *
     * @param conversationId 会话 ID
     * @return true 表示存在可恢复的 PauseState
     */
    public boolean hasInterruptedState(String conversationId) {
        if (stateStore == null || conversationId == null) {
            return false;
        }
        return stateStore.exists(conversationId);
    }

    /**
     * 获取指定会话的中断状态（用于应用层展示"上次有未完成任务"）。
     *
     * @param conversationId 会话 ID
     * @return PauseState，不存在返回 null
     */
    public PauseState getInterruptedState(String conversationId) {
        if (stateStore == null || conversationId == null) {
            return null;
        }
        return stateStore.findByConversationId(conversationId);
    }

    /**
     * 丢弃指定会话的中断状态（用户选择"放弃"）。
     *
     * @param conversationId 会话 ID
     * @return true 表示实际删除了状态
     */
    public boolean discardInterruptedState(String conversationId) {
        if (stateStore == null || conversationId == null) {
            return false;
        }
        return stateStore.delete(conversationId);
    }

    /**
     * 按会话 ID 从中断状态恢复流式执行。
     * <p>
     * 等价于：先从 {@link #getInterruptedState(String)} 取回 PauseState，
     * 再调用 {@link #resumeStream(PauseState, Map)}（空 toolResults，
     * 触发 pendingToolCalls 按既定策略重放）。
     *
     * @param conversationId 会话 ID
     * @return AgentStreamEvent 流；状态不存在时返回 Flux.error
     */
    public Flux<AgentStreamEvent> resumeStream(String conversationId) {
        return resumeStream(conversationId, null);
    }

    /**
     * 按会话 ID 从中断状态恢复流式执行，并附带用户中断后补充的新消息。
     * <p>
     * 用户中断后通常会想澄清或调整需求（如"换成 X 方向"），
     * {@code resumeQuery} 会作为新的 UserMessage 追加到恢复后的消息列表末尾，
     * 让 Agent 在继续执行前先看到用户的补充说明。
     *
     * @param conversationId 会话 ID
     * @param resumeQuery    用户中断后补充的新消息（可为 null）
     * @return AgentStreamEvent 流；状态不存在时返回 Flux.error
     */
    public Flux<AgentStreamEvent> resumeStream(String conversationId, String resumeQuery) {
        PauseState state = getInterruptedState(conversationId);
        if (state == null) {
            return Flux.error(new IllegalStateException(
                    "No interrupted state for conversation: " + conversationId));
        }
        return createExecutor().resumeStream(state, Map.of(), resumeQuery);
    }

    /**
     * 检查指定会话是否有正在运行的任务
     *
     * @param conversationId 会话 ID
     * @return 是否有正在运行的任务
     */
    public boolean hasRunningTask(String conversationId) {
        if (taskManager == null) {
            return false;
        }
        return taskManager.hasRunningTask(conversationId);
    }

    /**
     * 获取当前运行中的任务数量
     *
     * @return 任务数量
     */
    public int getRunningTaskCount() {
        if (taskManager == null) {
            return 0;
        }
        return taskManager.getTaskCount();
    }

    // ==================== Getters ====================

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 配置为 SubAgent 模式（由父 Agent 的 build() 在 wrap supplier 中调用）。
     * <p>
     * - 禁用 session：不记录 agentx_session<br>
     * - 注入父 TraceStore：复用父 Agent 的 trace 审计（受父 enableTrace 控制，null 则不记录）<br>
     * - profile memory 天然无：SubAgent 无 DataSource，不创建 memoryStore
     * <p>
     * createExecutor() 每次调用时读取这些字段，因此构建后设置即可生效。
     */
    void configureAsSubAgent(TraceStore parentTraceStore) {
        this.enableSession = false;
        this.traceStore = parentTraceStore;
    }

    public ChatClient getChatClient() {
        return chatClient;
    }

    public ChatModel getChatModel() {
        return chatModel;
    }

    public String getInstructions() {
        return instructions;
    }

    public MemoryStore getMemoryStore() {
        return memoryStore;
    }

    public List<Advisor> getAdvisors() {
        return advisors;
    }

    public List<ToolCallback> getTools() {
        return tools;
    }

    public AgentTaskManager getTaskManager() {
        return taskManager;
    }

    public int getMaxRounds() {
        return maxRounds;
    }

    /**
     * 合并多个工具数组，按工具名称去重。
     * 委托给 {@link ToolMergeUtil#mergeTools}。
     *
     * @param toolArrays 工具数组
     * @return 合并并去重后的工具数组
     */
    @SafeVarargs
    public static ToolCallback[] mergeTools(ToolCallback[]... toolArrays) {
        return ToolMergeUtil.mergeTools(toolArrays);
    }

    /**
     * Builder 模式构建 ReactAgent
     */
    public static class Builder {
        private String name;
        private String description;
        private ChatModel chatModel;
        private final List<ToolCallback> tools = new ArrayList<>();
        private final List<Advisor> advisors = new ArrayList<>();
        private int maxRounds = 100;
        private AgentTaskManager taskManager;
        private String instructions;
        private DataSource dataSource;
        private SemanticMemoryStore semanticMemoryStore;
        private boolean enableProfileMemory = true;
        private boolean enableSession = true;
        private boolean askUser = false;
        private final List<StageOutputProvider> stageOutputProviders = new ArrayList<>();
        private ThinkingMode thinkingMode = ThinkingMode.DISABLED;
        private int maxRetries = 3;
        private ContextPolicy contextPolicy;
        private DeferredToolRegistry deferredToolRegistry;
        private boolean enableTrace = true;
        private PauseStateStore stateStore;
        private int maxHistoryRounds = 30;
        private int maxHistoryTokens = 10000;
        private int sessionSummarizeStep = 5;
        private final List<Supplier<ReactAgent>> subAgentProviders = new ArrayList<>();

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        public Builder tools(ToolCallback... tools) {
            if (tools != null) {
                for (ToolCallback tool : tools) {
                    this.tools.add(tool);
                }
            }
            return this;
        }

        public Builder tools(List<ToolCallback> tools) {
            if (tools != null) {
                this.tools.addAll(tools);
            }
            return this;
        }

        public Builder advisors(Advisor... advisors) {
            this.advisors.addAll(List.of(advisors));
            return this;
        }

        public Builder advisors(List<Advisor> advisors) {
            if (advisors != null) {
                this.advisors.addAll(advisors);
            }
            return this;
        }

        public Builder maxRounds(int maxRounds) {
            this.maxRounds = maxRounds;
            return this;
        }

        public Builder taskManager(AgentTaskManager taskManager) {
            this.taskManager = taskManager;
            return this;
        }

        public Builder instructions(String instructions) {
            this.instructions = instructions;
            return this;
        }

        /**
         * 配置 DataSource，框架自动管理会话记忆和长期记忆。
         * <p>
         * 框架将自动创建：
         * - ChatMemory - 基于 AgentChatMemory（agentx_session 表）
         * - MemoryStore - 基于 JdbcMemoryStore（agentx_memory 表）
         * <p>
         * 长期记忆的读写由框架自动管理，不依赖 Agent 工具调用：
         * - 读：buildMemorySection() 在会话开始时自动注入 system prompt
         * - 写：MemoryExtractor 在对话结束后自动提取并保存
         * <p>
         * 依赖要求：spring-jdbc（或 spring-boot-starter-jdbc）
         *
         * @param dataSource 数据源
         */
        public Builder dataSource(DataSource dataSource) {
            this.dataSource = dataSource;
            return this;
        }

        /**
         * 配置语义记忆存储，启用第三层记忆（RAG 检索）。
         * <p>
         * 启用后，框架会：
         * - 每次对话后异步保存 Q&A 到 VectorStore
         * - 对话开始时通过语义检索注入相关历史知识
         * - Q&A 数量达到阈值（默认 30）后自动合并为摘要
         * <p>
         * 不传入此配置时，语义记忆不启用，只有短期记忆和用户画像。
         *
         * @param semanticMemoryStore 语义记忆配置（VectorStore + EmbeddingModel）
         */
        public Builder semanticMemoryStore(SemanticMemoryStore semanticMemoryStore) {
            this.semanticMemoryStore = semanticMemoryStore;
            return this;
        }

        /**
         * 是否启用用户画像记忆。
         * <p>
         * 启用后，框架会自动从对话中提取用户信息并持久化到 agentx_memory 表，
         * 在后续会话中自动注入到 system prompt。
         * <p>
         * 禁用后，框架只管理短期会话记忆（agentx_session），不做用户画像提取和注入。
         * 适用于对安全性要求较高的场景，防止用户通过对话诱导智能体生成不当记忆。
         * <p>
         * 默认启用。
         *
         * @param enabled true 启用（默认），false 禁用
         */
        public Builder enableProfileMemory(boolean enabled) {
            this.enableProfileMemory = enabled;
            return this;
        }

        /**
         * 是否启用会话历史记录（agentx_session）。
         * <p>
         * 禁用后，框架不保存对话历史到数据库，适用于 SubAgent 等无状态场景。
         * 默认启用。
         *
         * @param enabled true 启用（默认），false 禁用
         */
        public Builder enableSession(boolean enabled) {
            this.enableSession = enabled;
            return this;
        }

        /**
         * 启用 Human-in-the-Loop：Agent 可主动向用户提问。
         * <p>
         * 启用后框架自动：
         * - 注册 AskUserTool（LLM 可调用 ask_user 工具）
         * - 创建 PauseAdvisor 拦截 ask_user，暂停循环等待外部回答
         * <p>
         * 使用 callForResult / streamForResult 获取 AgentResult.Paused，
         * 然后调用 resume 继续对话。
         * <p>
         * 默认 false。需要自定义用户输入工具时，通过 {@code tools()} 注册工具，
         * 并通过 {@link PauseAdvisor.Builder#askUserTool(String)} 配置。
         *
         * @param askUser true 启用
         */
        public Builder askUser(boolean askUser) {
            this.askUser = askUser;
            return this;
        }

        /**
         * 注册自定义阶段输出提供者。
         * <p>
         * Provider 会在其声明的 {@link StageTiming} 时机被自动调用，
         * 产生的输出作为 {@link AgentStreamEvent.StageOutput} 事件推送给调用方。
         *
         * <p>示例：
         * <pre>{@code
         * .stageOutputProviders(
         *     new ReferenceProvider(),
         *     new RecommendProvider(chatModel)
         * )
         * }</pre>
         *
         * @param providers 阶段输出提供者
         */
        public Builder stageOutputProviders(StageOutputProvider... providers) {
            if (providers != null) {
                this.stageOutputProviders.addAll(List.of(providers));
            }
            return this;
        }

        /**
         * 注册自定义阶段输出提供者（List 形式）。
         *
         * @param providers 阶段输出提供者列表
         */
        public Builder stageOutputProviders(List<StageOutputProvider> providers) {
            if (providers != null) {
                this.stageOutputProviders.addAll(providers);
            }
            return this;
        }

        /**
         * 启用后，LLM 输出中的 &lt;think&gt;...&lt;/think&gt; 内容会被拆分为
         * {@link AgentStreamEvent.Thinking} 事件，标签外的内容为 {@link AgentStreamEvent.Text} 事件。
         * <p>
         * 适用于 MiniMax M2.7 <think>标签的模型。
         * 默认 false。
         *
         * @param enabled true 启用
         * @deprecated 使用 {@link #thinkingMode(ThinkingMode)} 替代，如 {@code thinkingMode(ThinkingMode.THINK_TAG)}
         */
        @Deprecated
        public Builder thinkTagEnabled(boolean enabled) {
            this.thinkingMode = enabled ? ThinkingMode.THINK_TAG : ThinkingMode.DISABLED;
            return this;
        }

        /**
         * 配置思考模型的输出格式。
         * <p>
         * 不同厂商的思考模型通过不同方式返回推理过程，调用方需根据模型类型选择对应模式：
         * <ul>
         *   <li>{@link ThinkingMode#THINK_TAG} - 思考内容嵌入 content 中的 &lt;think/&gt; 标签（MiniMax等）</li>
         *   <li>{@link ThinkingMode#REASONING_CONTENT} - 思考内容通过独立 reasoning_content 字段返回（DeepSeek、Qwen3.6 等）</li>
         * </ul>
         * <p>
         * 默认 {@link ThinkingMode#DISABLED}，不处理思考内容。
         *
         * @param thinkingMode 思考模式
         */
        public Builder thinkingMode(ThinkingMode thinkingMode) {
            this.thinkingMode = thinkingMode != null ? thinkingMode : ThinkingMode.DISABLED;
            return this;
        }

        /**
         * 配置 LLM 调用的最大重试次数。
         * <p>
         * 当 LLM 调用失败（网络异常、服务端错误等）时，框架自动重试。
         * 所有异常统一重试，不区分类型。
         * <p>
         * 流式模式下，重试时会发出 {@link AgentStreamEvent.Error} 事件通知调用方。
         * <p>
         * 默认 3 次。设为 0 禁用重试。
         *
         * @param maxRetries 最大重试次数
         */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * 配置上下文压缩策略，启用后 Agent 在每轮 LLM 调用前自动压缩历史消息。
         * <p>
         * 压缩策略包括：
         * <ul>
         *   <li>micro_compact: 替换旧工具结果和长参数为占位符</li>
         *   <li>auto_compact: token 超阈值时用 LLM 摘要替换旧消息</li>
         * </ul>
         * <p>
         * 不配置时上下文压缩不启用，Agent 行为完全不变。
         *
         * <p>示例：
         * <pre>{@code
         * // 默认配置
         * .contextPolicy(ContextPolicy.defaults())
         *
         * // 自定义配置
         * .contextPolicy(ContextPolicy.builder()
         *     .tokenThreshold(30000)
         *     .lastKeep(80)
         *     .build())
         * }</pre>
         *
         * @param contextPolicy 压缩策略
         */
        public Builder contextPolicy(ContextPolicy contextPolicy) {
            this.contextPolicy = contextPolicy;
            return this;
        }

        /**
         * 配置延迟加载工具，启用 ToolSearch 能力。
         * <p>
         * 启用后，这些工具不会一次性注入 ChatClient，而是由 LLM 按需搜索和加载。
         * LLM 在需要更多工具时调用 {@code tool_search} 元工具进行搜索。
         * <p>
         * 搜索模式：
         * <ul>
         *   <li>KEYWORD: 纯关键词匹配（Jieba 分词 + 打分排序）</li>
         *   <li>LLM: 纯 LLM 选择（构建精简 catalog，一次 LLM 调用）</li>
         *   <li>HYBRID: 先关键词匹配，无结果时 LLM 兜底（默认）</li>
         * </ul>
         * <p>
         * 不调用此方法时 ToolSearch 不启用，所有工具一次性注入，行为完全不变。
         *
         * <p>示例：
         * <pre>{@code
         * ReactAgent agent = ReactAgent.builder()
         *     .chatModel(chatModel)
         *     .tools(bashTool, readFileTool)              // alwaysLoad
         *     .deferredTools(ToolSearchConfig.defaults(),  // 搜索配置
         *         slackTool, emailTool, calendarTool)      // 延迟加载
         *     .build();
         * }</pre>
         *
         * @param config 搜索配置
         * @param tools  延迟加载的工具
         */
        public Builder deferredTools(ToolSearchConfig config, ToolCallback... tools) {
            if (tools != null && tools.length > 0) {
                this.deferredToolRegistry = DeferredToolRegistry.create(
                        config, List.of(tools), this.chatModel);
            }
            return this;
        }

        /**
         * 注册子 Agent。
         * <p>
         * 子 Agent 会被包装为 {@code call_{name}} 工具，主 Agent 的 LLM 根据描述自动决定是否委派。
         * 子 Agent 在独立 context window 中运行，只使用显式指定的工具，不与用户交互。
         *
         * <p>build() 时会调用一次 supplier 提取 name/description 元数据，后续每次实际调用再创建新实例。
         *
         * <p>约束（Phase 1）：
         * <ul>
         *   <li>子 Agent 必须设置 name 和 description</li>
         *   <li>禁止嵌套：子 Agent 不能再包含 SubAgentTool</li>
         *   <li>禁止 AskUser：子 Agent 不能与用户直接交互</li>
         *   <li>禁止 PauseAdvisor：子 Agent 不能暂停等人工</li>
         * </ul>
         *
         * <p>示例：
         * <pre>{@code
         * ReactAgent agent = ReactAgent.builder()
         *     .chatModel(chatModel)
         *     .tools(bashTool, readTool)
         *     .subAgent(() -> ReactAgent.builder()
         *         .name("expert")
         *         .description("领域专家，处理专业问题")
         *         .chatModel(chatModel)
         *         .instructions("你是领域专家...")
         *         .tools(readTool, grepTool)
         *         .build())
         *     .build();
         * }</pre>
         *
         * @param agentProvider 子 Agent 工厂（每次调用创建新实例，保证线程安全）
         */
        public Builder subAgent(Supplier<ReactAgent> agentProvider) {
            this.subAgentProviders.add(agentProvider);
            return this;
        }

        /**
         * 启用 LLM 调用审计（内置 {@link RequestLoggingAdvisor}）。
         * <p>
         * 启用后，每次 LLM 调用前会打印入参 JSON，并将请求/响应记录到 {@code agentx_trace} 表。
         * 需要同时配置 {@link #dataSource(DataSource)} 才会入库；仅有 dataSource 或仅有 enableTrace 均不满足。
         * <p>
         * 默认 true。
         *
         * @param enableTrace true 启用（默认），false 禁用
         */
        public Builder enableTrace(boolean enableTrace) {
            this.enableTrace = enableTrace;
            return this;
        }

        /**
         * 向后兼容：等同于 {@link #enableTrace(boolean)}。
         */
        public Builder requestLogging(boolean requestLogging) {
            this.enableTrace = requestLogging;
            return this;
        }

        /**
         * 配置 Agent 状态存储，启用用户主动中断的断点重连能力。
         * <p>
         * 未配置时默认使用 {@link InMemoryPauseStateStore}（单节点、进程内、TTL 7 天）。
         * 生产环境跨进程持久化请传入 JDBC / Redis 实现。
         *
         * @param stateStore 状态存储实例
         */
        public Builder stateStore(PauseStateStore stateStore) {
            this.stateStore = stateStore;
            return this;
        }

        /**
         * 设置对话历史加载的最大轮数，默认 30。
         * <p>
         * 传 0 或负值时关闭轮数限制（全量加载）。
         * 与 {@link #maxHistoryTokens(int)} 共同作用，先按轮数截断再按 token 截断。
         *
         * @param maxHistoryRounds 最大历史轮数，≤ 0 表示不限制
         */
        public Builder maxHistoryRounds(int maxHistoryRounds) {
            this.maxHistoryRounds = maxHistoryRounds;
            return this;
        }

        /**
         * 设置对话历史加载的估算 token 上限，默认 10000。
         * <p>
         * 传 0 或负值时关闭 token 限制。先按轮数加载，再按 token 从前往后截断完整 Q&A 对。
         *
         * @param maxHistoryTokens 最大估算 token 数，≤ 0 表示不限制
         */
        public Builder maxHistoryTokens(int maxHistoryTokens) {
            this.maxHistoryTokens = maxHistoryTokens;
            return this;
        }

        /**
         * 设置会话溢出摘要的步长，默认 5。
         * <p>
         * 当会话 qa_pair 数量超过 {@link #maxHistoryRounds(int)} 后，
         * 每累积 step 条溢出轮次才触发一次 session_summary 重新生成，
         * 避免相邻轮次频繁触发摘要。
         * <p>
         * 例如 maxHistoryRounds=30, step=5：第 35、40、45... 轮时触发。
         *
         * @param step 摘要步长，≤ 0 时关闭会话溢出摘要
         */
        public Builder sessionSummarizeStep(int step) {
            this.sessionSummarizeStep = step;
            return this;
        }

        public ReactAgent build() {
            Objects.requireNonNull(chatModel, "chatModel must not be null");

            SessionMessageStore sessionMessageStore = null;
            ConversationStore conversationStore = null;
            MemoryStore memoryStore = null;
            SemanticMemoryManager semanticMemoryManager = null;

            // 传入 DataSource 时，框架创建所有存储对象（session/memory/trace/conversation）
            // 是否实际写入由各 enableXXX 在运行时控制
            TraceStore traceStore = null;
            if (dataSource != null) {
                sessionMessageStore = DataSourceStorageFactory.createSessionMessageStore(dataSource);
                conversationStore = DataSourceStorageFactory.createConversationStore(dataSource);
                memoryStore = DataSourceStorageFactory.createMemoryStore(dataSource);
                traceStore = DataSourceStorageFactory.createTraceStore(dataSource);
            }

            // 传入 SemanticMemoryStore 时，启用语义记忆（第三层）
            if (semanticMemoryStore != null) {
                semanticMemoryManager = new SemanticMemoryManager(semanticMemoryStore, this.chatModel);
            }

            // taskManager 默认实例化：流式停止 / 用户主动中断都依赖它，
            // 不再要求调用方显式注入（与 stateStore 默认策略一致）
            if (taskManager == null) {
                taskManager = new AgentTaskManager();
            }

            // askUser=true 时自动注册内置 AskUserTool + PauseAdvisor
            if (askUser) {
                tools.addAll(List.of(AskUserTool.create()));

                boolean askUserCovered = advisors.stream()
                        .filter(a -> a instanceof PauseAdvisor)
                        .map(a -> (PauseAdvisor) a)
                        .anyMatch(pa -> pa.shouldIntercept("ask_user"));
                if (!askUserCovered) {
                    advisors.add(PauseAdvisor.builder().askUserTool("ask_user").build());
                }
            }

            // requestLogging=true 时自动注册内置 RequestLoggingAdvisor
            if (enableTrace) {
                advisors.add(new RequestLoggingAdvisor(chatModel));
            }

            // 处理 SubAgent：调一次 supplier 提取 name/description，注册为工具
            // wrap supplier：每次创建子 Agent 后自动配置为 SubAgent 模式
            // - 禁用 session（不记录 agentx_session）
            // - 注入父 TraceStore（受父 enableTrace 控制，要开一起开）
            final TraceStore subAgentTraceStore = this.enableTrace ? traceStore : null;
            for (Supplier<ReactAgent> provider : subAgentProviders) {
                ReactAgent prototype = provider.get();
                String agentName = prototype.getName();
                String agentDesc = prototype.getDescription();
                if (agentName == null || agentName.isBlank()) {
                    throw new IllegalArgumentException("SubAgent 必须设置 name");
                }
                Supplier<ReactAgent> wrappedProvider = () -> {
                    ReactAgent agent = provider.get();
                    agent.configureAsSubAgent(subAgentTraceStore);
                    return agent;
                };
                tools.add(new SubAgentTool.SubAgentToolCallback(agentName, agentDesc, wrappedProvider));
            }

            return new ReactAgent(this, sessionMessageStore, conversationStore, memoryStore,
                    semanticMemoryManager, traceStore,
                    stateStore != null ? stateStore : new InMemoryPauseStateStore());
        }
    }
}
