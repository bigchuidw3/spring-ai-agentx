package com.agentx.ai.core.hook;

import com.agentx.ai.core.stage.AgentRuntimeContext;

/**
 * Agent 调用开始事件（首次推理前）。
 *
 * <p>query/params/messages/emitter 均可通过 {@link #getRuntimeContext()} 访问。
 *
 * @author bigchui
 */
public final class BeforeCallEvent implements HookEvent {

    private final AgentRuntimeContext runtimeContext;

    public BeforeCallEvent(AgentRuntimeContext runtimeContext) {
        this.runtimeContext = runtimeContext;
    }

    public AgentRuntimeContext getRuntimeContext() {
        return runtimeContext;
    }
}
