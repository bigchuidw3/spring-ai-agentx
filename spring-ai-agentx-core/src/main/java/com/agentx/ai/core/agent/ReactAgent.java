package com.agentx.ai.core.agent;

import com.agentx.ai.core.agent.internal.AgentLoopExecutor;
import com.agentx.ai.core.agent.internal.AgentTaskManager;
import com.agentx.ai.core.context.ContextCompactor;
import com.agentx.ai.core.context.ContextPolicy;
import com.agentx.ai.core.advisors.PauseAdvisor;
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
import com.agentx.ai.core.tools.AskUserTool;
import com.agentx.ai.core.utils.ToolMergeUtil;
import com.agentx.ai.core.tools.toolsearch.DeferredToolRegistry;
import com.agentx.ai.core.tools.toolsearch.ToolSearchConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    private final ChatClient chatClient;
    private final ChatModel chatModel;
    private final int maxRounds;
    private final List<ToolCallback> tools;
    private final List<Advisor> advisors;
    private final AgentTaskManager taskManager;
    private final String instructions;
    private final ChatMemory chatMemory;
    private final MemoryStore memoryStore;
    private final SemanticMemoryManager semanticMemoryManager;
    private final DataSource dataSource;
    private final boolean profileMemoryEnabled;
    private final List<StageOutputProvider> stageOutputProviders;
    private final ThinkingMode thinkingMode;
    private final int maxRetries;
    private final ContextPolicy contextPolicy;
    private final DeferredToolRegistry deferredToolRegistry;

    private ReactAgent(Builder builder, ChatMemory chatMemory, MemoryStore memoryStore,
                       SemanticMemoryManager semanticMemoryManager) {
        this.deferredToolRegistry = builder.deferredToolRegistry;

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
        this.chatModel = builder.chatModel;
        this.maxRounds = builder.maxRounds;
        this.tools = List.copyOf(builder.tools);
        this.advisors = List.copyOf(builder.advisors);
        this.taskManager = builder.taskManager;
        this.instructions = builder.instructions;
        this.chatMemory = chatMemory;
        this.memoryStore = memoryStore;
        this.semanticMemoryManager = semanticMemoryManager;
        this.dataSource = builder.dataSource;
        this.profileMemoryEnabled = builder.profileMemoryEnabled;
        this.stageOutputProviders = builder.stageOutputProviders;
        this.thinkingMode = builder.thinkingMode;
        this.maxRetries = builder.maxRetries;
        this.contextPolicy = builder.contextPolicy;
    }

    /**
     * 创建 Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private AgentLoopExecutor createExecutor() {
        // 合并所有 PauseAdvisor 的拦截工具名和用户输入工具名
        // 兼容多个 PauseAdvisor 共存的场景
        // 例如：用户手动配 PauseAdvisor("write_file") + askUser(true) 自动加 PauseAdvisor(askUserTool="ask_user")
        PauseAdvisor pauseAdvisor = null;
        Set<String> allInterceptedTools = new HashSet<>();
        String mergedAskUserToolName = null;
        for (Advisor advisor : advisors) {
            if (advisor instanceof PauseAdvisor pa) {
                allInterceptedTools.addAll(pa.getInterceptToolNames());
                // 合并用户输入工具名（多个时 last-one-wins）
                if (pa.getAskUserToolName() != null) {
                    mergedAskUserToolName = pa.getAskUserToolName();
                }
            }
        }
        // 从拦截集合中移除用户输入工具，避免重复
        if (mergedAskUserToolName != null) {
            allInterceptedTools.remove(mergedAskUserToolName);
        }
        if (!allInterceptedTools.isEmpty() || mergedAskUserToolName != null) {
            pauseAdvisor = PauseAdvisor.builder()
                    .approvalTools(allInterceptedTools)
                    .askUserTool(mergedAskUserToolName)
                    .build();
        }

        var executorBuilder = AgentLoopExecutor.builder()
                .chatClient(chatClient)
                .maxRounds(maxRounds)
                .tools(tools)
                .taskManager(taskManager)
                .chatMemory(chatMemory)
                .instructions(instructions)
                .memoryStore(memoryStore)
                .chatModel(chatModel)
                .profileMemoryEnabled(profileMemoryEnabled)
                .pauseAdvisor(pauseAdvisor)
                .stageOutputProviders(stageOutputProviders)
                .thinkingMode(thinkingMode)
                .maxRetries(maxRetries)
                .advisors(advisors);

        // 上下文压缩（可选）
        if (this.contextPolicy != null) {
            executorBuilder.contextCompactor(new ContextCompactor(this.contextPolicy, this.chatModel));
        }

        // 语义记忆（可选第三层）
        if (semanticMemoryManager != null) {
            executorBuilder.semanticMemoryManager(semanticMemoryManager);
        }

        // 延迟工具注册（可选）
        if (deferredToolRegistry != null) {
            executorBuilder.deferredToolRegistry(deferredToolRegistry);
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
        private ChatModel chatModel;
        private final List<ToolCallback> tools = new ArrayList<>();
        private final List<Advisor> advisors = new ArrayList<>();
        private int maxRounds = 100;
        private AgentTaskManager taskManager;
        private String instructions;
        private DataSource dataSource;
        private SemanticMemoryStore semanticMemoryStore;
        private boolean profileMemoryEnabled = true;
        private boolean askUser = false;
        private final List<StageOutputProvider> stageOutputProviders = new ArrayList<>();
        private ThinkingMode thinkingMode = ThinkingMode.DISABLED;
        private int maxRetries = 3;
        private ContextPolicy contextPolicy;
        private DeferredToolRegistry deferredToolRegistry;

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
        public Builder profileMemoryEnabled(boolean enabled) {
            this.profileMemoryEnabled = enabled;
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
         * 启用<think></think>标签解析。
         * 适用于 MiniMax M2.7 等使用<think></think>标签的模型。
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
         *   <li>{@link ThinkingMode#THINK_TAG} - 思考内容嵌入 content 中的 &lt;think/&gt; 标签（MiniMax 等）</li>
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
         * // 自定义配置 + 保护 Skill 指令
         * .contextPolicy(ContextPolicy.builder()
         *     .tokenThreshold(30000)
         *     .protectedTools("SkillsTool")
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

        public ReactAgent build() {
            Objects.requireNonNull(chatModel, "chatModel must not be null");

            ChatMemory chatMemory = null;
            MemoryStore memoryStore = null;
            SemanticMemoryManager semanticMemoryManager = null;

            // 传入 DataSource 时，框架自动管理会话记忆和长期记忆
            if (dataSource != null) {
                chatMemory = DataSourceStorageFactory.createChatMemory(dataSource);
                memoryStore = DataSourceStorageFactory.createMemoryStore(dataSource);
            }

            // 传入 SemanticMemoryStore 时，启用语义记忆（第三层）
            if (semanticMemoryStore != null) {
                semanticMemoryManager = new SemanticMemoryManager(semanticMemoryStore, this.chatModel);
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

            return new ReactAgent(this, chatMemory, memoryStore, semanticMemoryManager);
        }
    }
}
