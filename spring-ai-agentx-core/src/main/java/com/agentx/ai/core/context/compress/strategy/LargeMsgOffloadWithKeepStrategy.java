package com.agentx.ai.core.context.compress.strategy;

import com.agentx.ai.core.context.compress.CompressionContext;

/**
 * L2 大消息 offload（保护 lastKeep）。
 * 扫描历史轮次区域，但最近 lastKeep 条消息不参与（与 L1 同边界）。
 *
 * @author bigchui
 */
public class LargeMsgOffloadWithKeepStrategy extends AbstractLargeMsgOffloadStrategy {

    @Override
    protected int scanEnd(CompressionContext ctx) {
        return ctx.historicalScanEnd(ctx.policy().lastKeep());
    }

    @Override
    public String name() {
        return "L2-LargeMsgOffload-WithKeep";
    }
}
