package com.agentx.ai.core.advisors;

import com.agentx.ai.core.model.AgentResult;
import com.agentx.ai.core.model.PendingToolCall;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 暂停拦截 Advisor，同时支持同步和流式调用。
 *
 * <p>拦截指定的工具调用，将它们标记为待处理（pending），从而暂停 Agent 执行循环。
 * <ul>
 *   <li>同步路径（{@link CallAdvisor}）：在 advisor chain 中拦截响应，通过 context 标记暂停状态</li>
 *   <li>流式路径（{@link StreamAdvisor}）：聚合流式响应后检查 tool call，同样通过 context 标记</li>
 * </ul>
 *
 * <p>配合 {@link com.agentx.ai.core.agent.ReactAgent#callForResult} 使用，
 * 当 Agent 调用了被拦截的工具时，会返回 {@link AgentResult.Paused}。
 *
 * <p>工具分类：
 * <ul>
 *   <li><b>审批工具（approvalTools）</b>：用户确认后由框架执行，用户拒绝则不执行。
 *       例如 write_file、delete_file 等危险操作</li>
 *   <li><b>用户输入工具（askUserTool）</b>：用户回答即工具结果，不需要框架执行。
 *       例如 ask_user、自定义表单工具等。只能配置一个</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * // 方式 1：全部为审批工具（向后兼容）
 * new PauseAdvisor("write_file", "delete_file")
 *
 * // 方式 2：Builder 模式，区分审批工具和用户输入工具
 * PauseAdvisor.builder()
 *     .approvalTools("write_file", "delete_file")
 *     .askUserTool("custom_ask")
 *     .build()
 *
 * // 方式 3：仅配置用户输入工具
 * PauseAdvisor.builder()
 *     .askUserTool("ask_user")
 *     .build()
 * }</pre>
 *
 * @author bigchui
 * 
 */
public class PauseAdvisor implements CallAdvisor, StreamAdvisor {

    public static final String PAUSE_REQUIRED = "pause.required";
    public static final String PENDING_TOOLS = "pause.pending.tools";

    /** 审批工具集合：用户确认后由框架执行 */
    private final Set<String> interceptToolNames;
    /** 用户输入工具：用户回答即结果，不执行（最多一个） */
    private final String askUserToolName;

    /**
     * 构造函数：所有工具均为审批工具（向后兼容）。
     *
     * @param interceptToolNames 拦截的工具名称集合
     */
    public PauseAdvisor(Set<String> interceptToolNames) {
        this.interceptToolNames = interceptToolNames != null ? interceptToolNames : Set.of();
        this.askUserToolName = null;
    }

    /**
     * 构造函数：所有工具均为审批工具（向后兼容）。
     *
     * @param toolNames 拦截的工具名称
     */
    public PauseAdvisor(String... toolNames) {
        this.interceptToolNames = toolNames != null ? Set.of(toolNames) : Set.of();
        this.askUserToolName = null;
    }

    /**
     * 内部构造函数，供 Builder 使用。
     */
    private PauseAdvisor(Set<String> interceptToolNames, String askUserToolName) {
        this.interceptToolNames = interceptToolNames != null ? interceptToolNames : Set.of();
        this.askUserToolName = askUserToolName;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);

        if (response.chatResponse() == null || !response.chatResponse().hasToolCalls()) {
            return response;
        }

        List<PendingToolCall> pending = findPendingToolCalls(response.chatResponse());
        if (!pending.isEmpty()) {
            response.context().put(PAUSE_REQUIRED, true);
            response.context().put(PENDING_TOOLS, pending);
        }

        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        Flux<ChatClientResponse> responseFlux = chain.nextStream(request);

        AtomicReference<ChatClientResponse> aggregatedRef = new AtomicReference<>();

        return responseFlux.publish(shared -> {
            // 流式透传所有 chunk，同时聚合最终响应
            Flux<ChatClientResponse> streaming = new ChatClientMessageAggregator()
                .aggregateChatClientResponse(shared, aggregatedRef::set);

            // 流结束后，检查聚合响应中是否有需要拦截的 tool call
            Flux<ChatClientResponse> pauseCheck = Flux.defer(() -> {
                ChatClientResponse aggregated = aggregatedRef.get();
                if (aggregated != null && aggregated.chatResponse() != null
                        && aggregated.chatResponse().hasToolCalls()) {
                    List<PendingToolCall> pending = findPendingToolCalls(aggregated.chatResponse());
                    if (!pending.isEmpty()) {
                        aggregated.context().put(PAUSE_REQUIRED, true);
                        aggregated.context().put(PENDING_TOOLS, pending);
                    }
                }
                return Flux.empty();
            });

            return streaming.concatWith(pauseCheck);
        });
    }

    /**
     * 类型安全地从 context 中获取暂停的工具调用列表。
     *
     * @param context ChatClientResponse 的 context
     * @return 暂停的工具调用列表，无暂停时返回 null
     */
    @SuppressWarnings("unchecked")
    public static List<PendingToolCall> getPendingTools(Map<String, Object> context) {
        return (List<PendingToolCall>) context.get(PENDING_TOOLS);
    }

    /**
     * 从 ChatResponse 中扫描需要拦截的工具调用。
     *
     * @param chatResponse LLM 响应
     * @return 需要拦截的工具调用列表，无拦截时返回空列表
     */
    private List<PendingToolCall> findPendingToolCalls(ChatResponse chatResponse) {
        List<PendingToolCall> pending = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : chatResponse.getResult().getOutput().getToolCalls()) {
            if (shouldIntercept(tc.name())) {
                pending.add(new PendingToolCall(tc.id(), tc.name(), tc.arguments()));
            }
        }
        return pending;
    }

    @Override
    public String getName() {
        return "PauseAdvisor";
    }

    @Override
    public int getOrder() {
        return 0;
    }

    /**
     * 判断指定工具是否需要拦截（包括审批工具和用户输入工具）。
     *
     * @param toolName 工具名称
     * @return 是否需要拦截
     */
    public boolean shouldIntercept(String toolName) {
        return interceptToolNames.contains(toolName) || toolName.equals(askUserToolName);
    }

    /**
     * 判断指定工具是否为用户输入工具。
     * <p>
     * 用户输入工具的特征：用户回答即工具结果，不需要框架执行。
     *
     * @param toolName 工具名称
     * @return 是否为用户输入工具
     */
    public boolean isAskUserTool(String toolName) {
        return toolName != null && toolName.equals(askUserToolName);
    }

    /**
     * 获取所有需要拦截的工具名称（包括审批工具和用户输入工具）。
     *
     * @return 拦截的工具名称集合
     */
    public Set<String> getInterceptToolNames() {
        if (askUserToolName == null) {
            return interceptToolNames;
        }
        Set<String> all = new HashSet<>(interceptToolNames);
        all.add(askUserToolName);
        return all;
    }

    /**
     * 获取用户输入工具名称。
     *
     * @return 用户输入工具名称，未配置时返回 null
     */
    public String getAskUserToolName() {
        return askUserToolName;
    }

    /**
     * 创建 Builder。
     *
     * @return Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder 模式构建 PauseAdvisor，支持区分审批工具和用户输入工具。
     */
    public static class Builder {
        private Set<String> approvalTools = Set.of();
        private String askUserToolName;

        /**
         * 配置审批工具名称。用户确认后由框架执行，用户拒绝则不执行。
         *
         * @param tools 审批工具名称
         * @return Builder
         */
        public Builder approvalTools(String... tools) {
            this.approvalTools = tools != null ? Set.of(tools) : Set.of();
            return this;
        }

        /**
         * 配置审批工具名称集合。
         *
         * @param tools 审批工具名称集合
         * @return Builder
         */
        public Builder approvalTools(Set<String> tools) {
            this.approvalTools = tools != null ? tools : Set.of();
            return this;
        }

        /**
         * 配置用户输入工具名称（最多一个）。
         * <p>
         * 用户输入工具的特征：用户回答即工具结果，不需要框架执行。
         * 用于 ask_user 或调用方自定义的输入类工具。
         *
         * @param toolName 用户输入工具名称
         * @return Builder
         */
        public Builder askUserTool(String toolName) {
            this.askUserToolName = toolName;
            return this;
        }

        /**
         * 构建 PauseAdvisor。
         *
         * @return PauseAdvisor 实例
         */
        public PauseAdvisor build() {
            return new PauseAdvisor(approvalTools, askUserToolName);
        }
    }
}
