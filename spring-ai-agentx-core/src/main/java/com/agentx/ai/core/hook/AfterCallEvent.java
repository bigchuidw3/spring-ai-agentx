package com.agentx.ai.core.hook;

import com.agentx.ai.core.stage.AgentRuntimeContext;

/**
 * Agent 调用结束事件（终态标记后、持久化前）。
 *
 * <p>只读：用于持久化、长期记忆、清理等后处理。
 * messages/totalTokens 均可通过 {@link #getRuntimeContext()} 访问。
 *
 * @author bigchui
 */
public final class AfterCallEvent implements HookEvent {

    private final AgentRuntimeContext runtimeContext;
    private final String finalAnswer;
    private final long durationMs;

    public AfterCallEvent(AgentRuntimeContext runtimeContext, String finalAnswer, long durationMs) {
        this.runtimeContext = runtimeContext;
        this.finalAnswer = finalAnswer;
        this.durationMs = durationMs;
    }

    public AgentRuntimeContext getRuntimeContext() {
        return runtimeContext;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public long getDurationMs() {
        return durationMs;
    }
}
