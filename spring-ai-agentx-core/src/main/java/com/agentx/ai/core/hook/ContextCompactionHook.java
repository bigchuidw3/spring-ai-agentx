package com.agentx.ai.core.hook;

import com.agentx.ai.core.context.ContextCompactor;
import com.agentx.ai.core.context.ContextPolicy;
import com.agentx.ai.core.stage.AgentRuntimeContext;

/**
 * 上下文压缩 Hook（按需引入）。
 *
 * <p>当用户配置 {@link ContextPolicy} 时由 {@code ReactAgent} 透明注册，
 * 订阅 {@link BeforeReasoningEvent}，在每轮 LLM 推理前执行 {@link ContextCompactor#compact}。
 * 优先级最高，确保压缩在任何用户 Hook 之前完成——用户 Hook 监听 BeforeReasoningEvent
 * 时看到的已是压缩后消息。
 *
 * @author bigchui
 */
public final class ContextCompactionHook implements AgentHook {

    private final ContextCompactor compactor;

    public ContextCompactionHook(ContextCompactor compactor) {
        this.compactor = compactor;
    }

    @Override
    public HookEvent onEvent(HookEvent event) {
        if (event instanceof BeforeReasoningEvent e) {
            AgentRuntimeContext ctx = e.getRuntimeContext();
            compactor.compact(
                    ctx.getMessages(),
                    ctx.getQuery(),
                    ctx.getConversationId(),
                    ctx.getSessionId());
        }
        return event;
    }

    @Override
    public int priority() {
        return Integer.MAX_VALUE;
    }
}
