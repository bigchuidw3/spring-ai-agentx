package com.agentx.ai.core.model;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * Agent 暂停状态快照。
 *
 * 包含恢复 Agent 执行所需的所有信息。调用方可将其序列化存储到 Session、Redis 等外部存储中，
 * 在用户提交答案后通过 {@link ReactAgent#resume(PauseState, java.util.Map)} 恢复执行。
 *
 * 使用示例：
 * {@code
 * if (result instanceof AgentResult.Paused p) {
 *     PauseState state = p.state();
 *     // 展示 state.getPendingToolCalls() 中的问题给用户
 *     // 用户回答后:
 *     agent.resume(state, Map.of(toolCallId, userAnswer));
 * }
 * }
 *
 * @author bigchui
 * 
 */
public class PauseState {

    private final List<Message> messages;
    private final int currentRound;
    private final List<PendingToolCall> pendingToolCalls;
    private final RunnableParams params;
    private final String query;

    private PauseState(Builder builder) {
        this.messages = builder.messages;
        this.currentRound = builder.currentRound;
        this.pendingToolCalls = builder.pendingToolCalls;
        this.params = builder.params;
        this.query = builder.query;
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

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private List<Message> messages;
        private int currentRound;
        private List<PendingToolCall> pendingToolCalls;
        private RunnableParams params;
        private String query;

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

        public PauseState build() {
            return new PauseState(this);
        }
    }
}
