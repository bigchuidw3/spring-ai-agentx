package com.agentx.ai.core.context.compress.l3;

import com.agentx.ai.core.context.compress.CompressionContext;
import com.agentx.ai.core.context.compress.l2.AbstractLargeMsgOffloadStrategy;

/**
 * L3 大消息 offload（不保护 lastKeep）。
 * 仅保护最新 AssistantMessage，扫描整个历史轮次区域。
 * 仅在 L2 未触发时执行（外层策略链顺序保证）。
 *
 * @author bigchui
 */
public class LargeMsgOffloadNoKeepStrategy extends AbstractLargeMsgOffloadStrategy {

    @Override
    protected int scanEnd(CompressionContext ctx) {
        int latestUser = ctx.latestUserMsgIndex();
        if (latestUser < 0) {
            return ctx.messages().size();
        }
        return latestUser;
    }

    @Override
    public String name() {
        return "L3-LargeMsgOffload-NoKeep";
    }
}
