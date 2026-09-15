package com.agentx.ai.rag.common;

/**
 * 分块唯一标识生成器。
 *
 * @author bigchui
 */
@FunctionalInterface
public interface ChunkIdGenerator {

    /**
     * 生成下一个唯一标识。
     *
     * @return 唯一标识，不允许为空
     */
    String nextId();
}
