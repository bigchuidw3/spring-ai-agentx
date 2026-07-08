package com.agentx.ai.core.model;

import com.agentx.ai.core.interrupt.SafePoint;
import com.agentx.ai.core.interrupt.PauseReason;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * Agent 暂停状态快照。
 *
 * <p>包含恢复 Agent 执行所需的所有信息。调用方可将其序列化存储到 Session、Redis 等外部存储中，
 * 在用户提交答案后通过 {@link com.agentx.ai.core.agent.ReactAgent#resume(PauseState, java.util.Map)}
 * 或 {@link com.agentx.ai.core.agent.ReactAgent#resumeStream(PauseState, java.util.Map)} 恢复执行。
 *
 * <h2>两类暂停场景</h2>
 * <ul>
 *   <li><b>HITL 工具审批</b>（{@link PauseReason#HITL_TOOL_REQUEST}）：
 *       由 PauseAdvisor 在工具执行前拦截触发。pendingToolCalls 中的工具尚未执行，
 *       恢复时由用户提供答案或确认，框架按用户回答决定执行或跳过</li>
 *   <li><b>用户主动中断</b>（{@link PauseReason#USER_INTERRUPT}）：
 *       由 {@code ReactAgent.interrupt(convId, msg)} 触发。
 *       此时可能正在 LLM 流式生成或工具执行中。
 *       {@link #safePoint} 标识具体安全点</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * if (result instanceof AgentResult.Paused p) {
 *     PauseState state = p.state();
 *     if (state.getReason() == PauseReason.HITL_TOOL_REQUEST) {
 *         // 展示 state.getPendingToolCalls() 中的问题给用户
 *         // 用户回答后:
 *         agent.resume(state, Map.of(toolCallId, userAnswer));
 *     } else if (state.getReason() == PauseReason.USER_INTERRUPT) {
 *         // 持久化 state，下次调用 resumeStream 恢复
 *         stateStore.save(state);
 *     }
 * }
 * }</pre>
 *
 * @author bigchui
 */
public class PauseState {

    private final List<Message> messages;
    private final int currentRound;
    private final List<PendingToolCall> pendingToolCalls;
    private final RunnableParams params;
    private final String query;
    private final long sessionId;
    private final long totalPromptTokens;
    private final long totalCompletionTokens;

    /**
     * 暂停原因：HITL 或 用户中断。null 表示旧版本数据，按 HITL 处理
     */
    private final PauseReason reason;
    /**
     * 安全点 / 中断阶段
     */
    private final SafePoint safePoint;
    /**
     * 中断/暂停附带的用户消息（如"用户主动中断"说明）
     */
    private final String interruptMessage;
    /**
     * 中断触发的时间戳（epoch millis）
     */
    private final long interruptedAt;
    /**
     * 中断时正在执行的 SubAgent 引用列表（仅 USER_INTERRUPT 且含 SubAgent 时有效）
     */
    private final List<PauseState> children;

    private PauseState(Builder builder) {
        this.messages = builder.messages;
        this.currentRound = builder.currentRound;
        this.pendingToolCalls = builder.pendingToolCalls;
        this.params = builder.params;
        this.query = builder.query;
        this.sessionId = builder.sessionId;
        this.totalPromptTokens = builder.totalPromptTokens;
        this.totalCompletionTokens = builder.totalCompletionTokens;
        this.reason = builder.reason;
        this.safePoint = builder.safePoint;
        this.interruptMessage = builder.interruptMessage;
        this.interruptedAt = builder.interruptedAt;
        this.children = builder.children;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public int getCurrentRound() {
        return currentRound;
    }

    public List<PendingToolCall> getPendingToolCalls() {
        return pendingToolCalls;
    }

    public RunnableParams getParams() {
        return params;
    }

    public String getQuery() {
        return query;
    }

    public long getSessionId() {
        return sessionId;
    }

    public long getTotalPromptTokens() {
        return totalPromptTokens;
    }

    public long getTotalCompletionTokens() {
        return totalCompletionTokens;
    }

    /**
     * 暂停原因。null 表示旧版本数据（按 HITL_TOOL_REQUEST 处理）。
     */
    public PauseReason getReason() {
        return reason;
    }

    /**
     * 中断所在安全点（仅 USER_INTERRUPT 时有意义）。null 表示未记录。
     */
    public SafePoint getSafePoint() {
        return safePoint;
    }

    /**
     * 中断/暂停附带的用户消息说明。
     */
    public String getInterruptMessage() {
        return interruptMessage;
    }

    /**
     * 中断触发的时间戳（epoch millis）。0 表示未记录。
     */
    public long getInterruptedAt() {
        return interruptedAt;
    }

    /**
     * 中断时正在执行的 SubAgent 引用列表。null 或空表示无 SubAgent 在执行。
     */
    public List<PauseState> getChildren() {
        return children;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private List<Message> messages;
        private int currentRound;
        private List<PendingToolCall> pendingToolCalls;
        private RunnableParams params;
        private String query;
        private long sessionId;
        private long totalPromptTokens;
        private long totalCompletionTokens;
        private PauseReason reason;
        private SafePoint safePoint;
        private String interruptMessage;
        private long interruptedAt;
        private List<PauseState> children;

        public Builder messages(List<Message> messages) {
            this.messages = messages;
            return this;
        }

        public Builder currentRound(int currentRound) {
            this.currentRound = currentRound;
            return this;
        }

        public Builder pendingToolCalls(List<PendingToolCall> pendingToolCalls) {
            this.pendingToolCalls = pendingToolCalls;
            return this;
        }

        public Builder params(RunnableParams params) {
            this.params = params;
            return this;
        }

        public Builder query(String query) {
            this.query = query;
            return this;
        }

        public Builder sessionId(long sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder totalPromptTokens(long totalPromptTokens) {
            this.totalPromptTokens = totalPromptTokens;
            return this;
        }

        public Builder totalCompletionTokens(long totalCompletionTokens) {
            this.totalCompletionTokens = totalCompletionTokens;
            return this;
        }

        public Builder reason(PauseReason reason) {
            this.reason = reason;
            return this;
        }

        public Builder safePoint(SafePoint safePoint) {
            this.safePoint = safePoint;
            return this;
        }

        public Builder interruptMessage(String interruptMessage) {
            this.interruptMessage = interruptMessage;
            return this;
        }

        public Builder interruptedAt(long interruptedAt) {
            this.interruptedAt = interruptedAt;
            return this;
        }

        public Builder children(List<PauseState> children) {
            this.children = children;
            return this;
        }

        public PauseState build() {
            return new PauseState(this);
        }
    }
}
