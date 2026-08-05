package com.agentx.ai.core.hook;

import com.agentx.ai.core.stage.AgentRuntimeContext;

/**
 * 异常事件（LLM 或工具执行抛异常时）。
 *
 * <p>只读：用于错误告警、日志、清理。
 *
 * @author bigchui
 */
public final class ErrorEvent implements HookEvent {

    private final AgentRuntimeContext runtimeContext;
    private final Throwable error;
    private final String phase;
    private final int retryAttempt;
    private final boolean willRetry;

    public ErrorEvent(AgentRuntimeContext runtimeContext, Throwable error, String phase,
                      int retryAttempt, boolean willRetry) {
        this.runtimeContext = runtimeContext;
        this.error = error;
        this.phase = phase;
        this.retryAttempt = retryAttempt;
        this.willRetry = willRetry;
    }

    public AgentRuntimeContext getRuntimeContext() {
        return runtimeContext;
    }

    public Throwable getError() {
        return error;
    }

    public String getPhase() {
        return phase;
    }

    public int getRetryAttempt() {
        return retryAttempt;
    }

    public boolean isWillRetry() {
        return willRetry;
    }
}
