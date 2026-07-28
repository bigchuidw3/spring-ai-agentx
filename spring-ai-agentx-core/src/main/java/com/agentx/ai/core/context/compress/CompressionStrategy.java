package com.agentx.ai.core.context.compress;

/**
 * 上下文压缩策略接口（责任链节点）。
 * 每个策略实现一层压缩逻辑，返回 true 表示已触发压缩、终止链；返回 false 表示未触发、继续下一个。
 *
 * @author bigchui
 */
public interface CompressionStrategy {

    /**
     * 尝试执行本层压缩。
     *
     * @param ctx 压缩上下文（含可变消息列表、分区索引、offload 存储）
     * @return true 表示已产生压缩、终止策略链；false 表示未触发、继续下一层
     */
    boolean tryCompress(CompressionContext ctx);

    /**
     * 策略名称，用于日志与 trace。
     */
    default String name() {
        return getClass().getSimpleName();
    }
}
