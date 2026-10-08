package com.agentx.ai.core.context.compress.strategy;

import com.agentx.ai.core.context.compress.CompressionContext;

/**
 * L3 大消息 offload（不保护 lastKeep）。
 * 扫描上界放宽到「最后一个含 tool_calls 的 AssistantMessage 之前」：
 * 既能 offload 历史区的大消息，也能 offload 当前轮里已经「看过」的超长工具结果，
 * 只保护 LLM 下一轮正在等待的最新工具结果。仅在 L2 未触发时执行（外层策略链顺序保证）。
 *
 * @author bigchui
 */
public class LargeMsgOffloadNoKeepStrategy extends AbstractLargeMsgOffloadStrategy {

    @Override
    protected int scanEnd(CompressionContext ctx) {
        return ctx.latestToolCallBoundary();
    }

    @Override
    public String name() {
        return "L3-LargeMsgOffload-NoKeep";
    }
}
