package com.agentx.ai.core.tools;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.agent.internal.SubAgentInterruptRegistry;
import com.agentx.ai.core.model.AgentResult;
import com.agentx.ai.core.model.AgentStreamEvent;
import com.agentx.ai.core.model.PauseState;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.model.SubAgentSource;
import com.agentx.ai.core.advisors.PauseAdvisor;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * SubAgent 工具 — 将 ReactAgent 包装为 ToolCallback。
 *
 * <p>核心职责：
 * <ul>
 *   <li>接收主 Agent LLM 的委派请求</li>
 *   <li>通过 Supplier 创建独立的子 ReactAgent 实例</li>
 *   <li>透传 RunnableParams，保持调用上下文一致</li>
 *   <li>流式模式：子 Agent stream，事件打 source 标签转发到父 Sink</li>
 *   <li>非流式模式：子 Agent call，直接返回结果</li>
 * </ul>
 *
 * <p>子 Agent 约束（Phase 1）：
 * <ul>
 *   <li>只能使用普通工具，禁止嵌套 SubAgentTool</li>
 *   <li>禁止使用 AskUserTool</li>
 *   <li>禁止使用 PauseAdvisor</li>
 * </ul>
 *
 * @author bigchui
 */
public class SubAgentTool {

    private static final Logger log = LoggerFactory.getLogger(SubAgentTool.class);

    /** 动态派生（spawn_subagent）的默认工具描述，调用方未显式传入时使用。 */
    private static final String DEFAULT_DYNAMIC_DESCRIPTION = "派生一个临时子 Agent 独立/并行处理子任务";

    /**
     * 创建 SubAgentTool 的 ToolCallback。
     *
     * @param agentName     子 Agent 名称（工具名称自动生成为 call_{name}）
     * @param description   工具描述（LLM 根据此描述决定是否委派）
     * @param agentProvider 子 Agent 工厂（每次调用创建新实例）
     * @return ToolCallback
     */
    public static ToolCallback create(String agentName, String description,
                                      Supplier<ReactAgent> agentProvider) {
        String toolName = "call_" + agentName;
        String toolDescription = description
                + "。将任务委派给此子Agent处理。message 为任务描述；"
                + "该子Agent按名称保留会话记忆，同名调用自动续接之前的会话。";

        Function<SubAgentRequest, String> fn = request -> execute(
                agentName, agentProvider, request.message());

        ToolCallback delegate = FunctionToolCallback.builder(toolName, fn)
                .description(toolDescription)
                .inputType(SubAgentRequest.class)
                .build();
        return new SubAgentToolCallback(delegate);
    }

    /**
     * 创建动态子 Agent 工具（spawn_subagent）：由 LLM 在调用时给定名字与任务，现建/续接一个子 Agent。
     * agentFactory 返回子 Agent 的 Builder（只填 chatModel/tools 等差异项），name/instructions/agentKey 由框架自动设置。
     */
    public static ToolCallback createDynamic(Function<DynamicSubAgentRequest, ReactAgent.Builder> agentFactory) {
        return createDynamic(DEFAULT_DYNAMIC_DESCRIPTION, agentFactory);
    }

    public static ToolCallback createDynamic(String description,
                                             Function<DynamicSubAgentRequest, ReactAgent.Builder> agentFactory) {
        String toolDescription = description
                + "。name 为子 Agent 名称，task 为任务描述；"
                + "name 是子 Agent 的唯一标识：续接之前同名子 Agent 的会话（保留记忆）用相同 name，另起全新会话用新 name。";

        Function<DynamicSubAgentRequest, String> fn = request -> executeDynamic(agentFactory, request);

        ToolCallback delegate = FunctionToolCallback.builder("spawn_subagent", fn)
                .description(toolDescription)
                .inputType(DynamicSubAgentRequest.class)
                .build();
        return new SubAgentToolCallback(delegate);
    }

    /**
     * 执行动态子 Agent：按 LLM 给定的名字 + 任务现建子 Agent，统一走 runSubAgent。
     */
    private static String executeDynamic(Function<DynamicSubAgentRequest, ReactAgent.Builder> agentFactory,
                                         DynamicSubAgentRequest req) {
        String task = req.task() != null ? req.task() : "";
        ReactAgent.Builder builder = agentFactory.apply(req);
        // 调用方未显式设置时，用 LLM 传入的 name/task 补齐；已设置则保留
        String agentName = builder.getName();
        if (agentName == null || agentName.isBlank()) {
            agentName = (req.name() != null && !req.name().isBlank()) ? req.name() : "worker";
            builder.name(agentName);
        }
        if (builder.getInstructions() == null || builder.getInstructions().isBlank()) {
            builder.instructions(task);
        }
        ReactAgent subAgent = builder.build();
        // 自动补子 Agent 身份：agentKey 默认取 name（调用方已显式指定则保留）
        subAgent.configureAsSubAgent(null, null, ContextHolder.getParentTracer());
        return runSubAgent(agentName, subAgent, task,
                ContextHolder.getChildSnapshot());
    }

    /**
     * 执行预定义子 Agent：带子快照（恢复）走续跑，否则新建执行。统一走 runSubAgent。
     */
    private static String execute(String agentName, Supplier<ReactAgent> agentProvider,
                                  String message) {
        return runSubAgent(agentName, agentProvider.get(), message,
                ContextHolder.getChildSnapshot());
    }

    /**
     * 统一执行子 Agent（新建或续跑）：配置父子溯源 → 合规检查 → 中断注册 → 执行 → 注销。
     * child 非空走快照续跑，否则按父 sink 是否为空分流式/非流式。
     */
    private static String runSubAgent(String agentName, ReactAgent subAgent, String message,
                                      PauseState child) {
        String parentToolCallId = child != null
                ? child.getParentToolCallId() : ContextHolder.getParentToolCallId();
        subAgent.configureSubAgentParent(ContextHolder.getParentSessionId(), parentToolCallId);
        assertAllowed(subAgent, agentName);

        RunnableParams params = ContextHolder.getParams();
        if (params == null) {
            params = RunnableParams.empty();
        }
        String conversationId = params.getConversationId();
        if (conversationId != null && parentToolCallId != null) {
            SubAgentInterruptRegistry.register(conversationId, parentToolCallId, subAgent.getTaskManager());
        }
        try {
            if (child != null) {
                return resumeStreaming(agentName, subAgent, child, ContextHolder.getSink());
            }
            Sinks.Many<AgentStreamEvent> parentSink = ContextHolder.getSink();
            return (parentSink != null)
                    ? executeStreaming(agentName, subAgent, message, params, parentSink)
                    : executeBlocking(subAgent, message, params);
        } finally {
            if (conversationId != null && parentToolCallId != null) {
                SubAgentInterruptRegistry.unregister(conversationId, parentToolCallId);
            }
        }
    }

    /**
     * 流式恢复：子 Agent resumeStream 续跑，事件打 source 标签转发到父 Sink，收集文本结果。
     */
    private static String resumeStreaming(String agentName, ReactAgent subAgent, PauseState child,
                                          Sinks.Many<AgentStreamEvent> parentSink) {
        return collectStream(agentName, subAgent.resumeStream(child, Map.of()), parentSink, "resume streaming");
    }

    /**
     * 流式执行：子 Agent stream，事件打 source 标签转发到父 Sink。
     */
    private static String executeStreaming(String agentName, ReactAgent subAgent, String message,
                                           RunnableParams params, Sinks.Many<AgentStreamEvent> parentSink) {
        return collectStream(agentName, subAgent.streamForResult(message, params), parentSink, "streaming execution");
    }

    /**
     * 收集子 Agent 流式输出：事件打 source 转发到父 Sink，文本聚合为结果。
     * Paused 不转发（子 Agent 中断状态由 master 统一管理，master 的 Paused 已含 children）。
     */
    private static String collectStream(String agentName, Flux<AgentStreamEvent> stream,
                                        Sinks.Many<AgentStreamEvent> parentSink, String phase) {
        String subAgentId = "sa_" + IdWorker.getIdStr();
        SubAgentSource source = new SubAgentSource(agentName, subAgentId);

        log.debug("SubAgent[{}] {} started, subAgentId={}", agentName, phase, subAgentId);

        StringBuilder result = new StringBuilder();
        stream.map(event -> withSource(event, source))
                .filter(event -> !(event instanceof AgentStreamEvent.Paused))
                .doOnNext(parentSink::tryEmitNext)
                .filter(e -> e instanceof AgentStreamEvent.Text)
                .map(e -> ((AgentStreamEvent.Text) e).content())
                .doOnNext(result::append)
                .blockLast();

        log.debug("SubAgent[{}] {} completed, subAgentId={}", agentName, phase, subAgentId);
        return result.toString();
    }

    /**
     * 非流式执行：子 Agent call，直接返回结果。
     */
    private static String executeBlocking(ReactAgent subAgent, String message,
                                          RunnableParams params) {
        log.debug("SubAgent blocking execution started");

        AgentResult agentResult = subAgent.callForResult(message, params);
        if (agentResult instanceof AgentResult.Completed c) {
            return c.answer();
        }
        if (agentResult instanceof AgentResult.Failed f) {
            return "SubAgent 执行失败：" + f.error();
        }
        if (agentResult instanceof AgentResult.Paused) {
            return "SubAgent 执行被中断";
        }
        return "SubAgent 执行异常：未知结果类型";
    }

    /**
     * 给子 Agent 的事件打上 source 标签。
     */
    private static AgentStreamEvent withSource(AgentStreamEvent event, SubAgentSource source) {
        return switch (event) {
            case AgentStreamEvent.Thinking e -> new AgentStreamEvent.Thinking(e.content(), source);
            case AgentStreamEvent.Text e -> new AgentStreamEvent.Text(e.content(), source);
            case AgentStreamEvent.ToolStart e ->
                    new AgentStreamEvent.ToolStart(e.toolName(), e.toolCallId(), e.arguments(), source);
            case AgentStreamEvent.ToolEnd e ->
                    new AgentStreamEvent.ToolEnd(e.toolName(), e.toolCallId(), e.result(), source);
            case AgentStreamEvent.Paused e -> new AgentStreamEvent.Paused(e.state(), source);
            case AgentStreamEvent.Error e -> new AgentStreamEvent.Error(e.code(), e.message(), e.detail(), source);
            case AgentStreamEvent.Complete e -> new AgentStreamEvent.Complete(
                    e.totalPromptTokens(), e.totalCompletionTokens(), source);
            default -> event;
        };
    }

    /**
     * 合规检查：验证子 Agent 配置符合约束。
     */
    private static void assertAllowed(ReactAgent agent, String agentName) {
        for (ToolCallback tool : agent.getTools()) {
            String toolDefName = tool.getToolDefinition().name();
            if (isSubAgentTool(tool)) {
                throw new IllegalArgumentException(
                        "SubAgent[" + agentName + "] 不允许嵌套使用 SubAgentTool");
            }
            if ("AskUser".equals(toolDefName) || "ask_user".equals(toolDefName)) {
                throw new IllegalArgumentException(
                        "SubAgent[" + agentName + "] 不允许使用 AskUser 工具");
            }
        }
        for (var advisor : agent.getAdvisors()) {
            if (advisor instanceof PauseAdvisor) {
                throw new IllegalArgumentException(
                        "SubAgent[" + agentName + "] 不允许使用 PauseAdvisor");
            }
        }
    }

    /** 工具名是否是派生子 Agent 的工具（spawn_subagent / call_xxx）。 */
    public static boolean isSubAgentTool(String toolName) {
        return "spawn_subagent".equals(toolName) || (toolName != null && toolName.startsWith("call_"));
    }

    private static boolean isSubAgentTool(ToolCallback tool) {
        return tool instanceof SubAgentToolCallback;
    }

    // ==================== 上下文传递 ====================

    /**
     * 线程局部变量，用于在 FunctionToolCallback 调用链中传递 Sink 和 Params。
     * <p>
     * 因为 Spring AI 的 FunctionToolCallback 不直接传递 ToolContext 到 Function，
     * 所以通过 ThreadLocal 在 ToolCallExecutor 设置，SubAgentTool 读取。
     */
    public static class ContextHolder {
        private static final ThreadLocal<Sinks.Many<AgentStreamEvent>> SINK = new ThreadLocal<>();
        private static final ThreadLocal<RunnableParams> PARAMS = new ThreadLocal<>();
        private static final ThreadLocal<String> PARENT_SESSION_ID = new ThreadLocal<>();
        private static final ThreadLocal<String> PARENT_TOOL_CALL_ID = new ThreadLocal<>();
        private static final ThreadLocal<PauseState> CHILD_SNAPSHOT = new ThreadLocal<>();
        private static final ThreadLocal<Tracer> PARENT_TRACER = new ThreadLocal<>();

        public static void set(Sinks.Many<AgentStreamEvent> sink, RunnableParams params,
                               String parentSessionId, String parentToolCallId) {
            SINK.set(sink);
            PARAMS.set(params);
            PARENT_SESSION_ID.set(parentSessionId);
            PARENT_TOOL_CALL_ID.set(parentToolCallId);
        }

        public static void setChildSnapshot(PauseState child) {
            CHILD_SNAPSHOT.set(child);
        }

        public static void setParentTracer(Tracer tracer) {
            PARENT_TRACER.set(tracer);
        }

        public static Tracer getParentTracer() {
            return PARENT_TRACER.get();
        }

        public static PauseState getChildSnapshot() {
            return CHILD_SNAPSHOT.get();
        }

        public static Sinks.Many<AgentStreamEvent> getSink() {
            return SINK.get();
        }

        public static RunnableParams getParams() {
            return PARAMS.get();
        }

        public static String getParentSessionId() {
            return PARENT_SESSION_ID.get();
        }

        public static String getParentToolCallId() {
            return PARENT_TOOL_CALL_ID.get();
        }

        public static void clear() {
            SINK.remove();
            PARAMS.remove();
            PARENT_SESSION_ID.remove();
            PARENT_TOOL_CALL_ID.remove();
            CHILD_SNAPSHOT.remove();
            PARENT_TRACER.remove();
        }
    }

    /**
     * 包装的 ToolCallback，在 call 前设置上下文，call 后清理。
     */
    public static class SubAgentToolCallback implements ToolCallback {
        private final ToolCallback delegate;
        private volatile Tracer parentTracer;

        public SubAgentToolCallback(ToolCallback delegate) {
            this.delegate = delegate;
        }

        /** 由父 Agent build() 时注入父 Tracer，供动态派生子 Agent 复用产出可观测性。 */
        public void configureParentTracer(Tracer tracer) {
            this.parentTracer = tracer;
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            // 从 ToolContext 提取 sink / params / 父调用信息，设置到 ThreadLocal
            Sinks.Many<AgentStreamEvent> sink = null;
            RunnableParams params = null;
            String parentSessionId = null;
            String parentToolCallId = null;
            PauseState childSnapshot = null;
            if (toolContext != null && toolContext.getContext() != null) {
                Object sinkObj = toolContext.getContext().get("eventSink");
                if (sinkObj instanceof Sinks.Many) {
                    sink = (Sinks.Many<AgentStreamEvent>) sinkObj;
                }
                Object paramsObj = toolContext.getContext().get("runnableParams");
                if (paramsObj instanceof RunnableParams) {
                    params = (RunnableParams) paramsObj;
                }
                Object sessionObj = toolContext.getContext().get("parentSessionId");
                if (sessionObj != null) {
                    parentSessionId = sessionObj.toString();
                }
                Object toolCallObj = toolContext.getContext().get("parentToolCallId");
                if (toolCallObj != null) {
                    parentToolCallId = toolCallObj.toString();
                }
                Object childObj = toolContext.getContext().get("subAgentChildSnapshot");
                if (childObj instanceof PauseState child) {
                    childSnapshot = child;
                }
            }
            try {
                ContextHolder.set(sink, params, parentSessionId, parentToolCallId);
                ContextHolder.setChildSnapshot(childSnapshot);
                ContextHolder.setParentTracer(parentTracer);
                return delegate.call(toolInput, toolContext);
            } finally {
                ContextHolder.clear();
            }
        }

        @Override
        public String call(String toolInput) {
            return delegate.call(toolInput);
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }
    }

    // ==================== 请求参数 ====================

    /**
     * SubAgent 工具的输入参数。
     */
    public record SubAgentRequest(
            String message
    ) {
    }

    /**
     * 动态子 Agent 工具的输入参数：名字（作为 agentKey）+ 任务描述。
     */
    public record DynamicSubAgentRequest(
            String name,
            String task
    ) {
    }
}
