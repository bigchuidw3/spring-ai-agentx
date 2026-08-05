package com.agentx.ai.core.hook;

import com.agentx.ai.core.stage.AgentRuntimeContext;

/**
 * 单轮推理开始事件（LLM stream 调用前、上下文压缩后）。
 *
 * <p>可修改：通过 {@link #getRuntimeContext()} 获取 messages 列表可增删（如压缩 Hook 移除旧消息）。
 *
 * @author bigchui
 */
public final class BeforeReasoningEvent implements HookEvent {

    private final AgentRuntimeContext runtimeContext;
    private final long round;

    public BeforeReasoningEvent(AgentRuntimeContext runtimeContext, long round) {
        this.runtimeContext = runtimeContext;
        this.round = round;
    }

    public AgentRuntimeContext getRuntimeContext() {
        return runtimeContext;
    }

    public long getRound() {
        return round;
    }
}
