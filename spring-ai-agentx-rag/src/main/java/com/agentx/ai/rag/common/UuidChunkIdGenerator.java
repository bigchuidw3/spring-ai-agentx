package com.agentx.ai.rag.common;

import java.util.UUID;

/**
 * 基于 UUID 的默认 chunkId 生成器。
 *
 * @author bigchui
 */
public final class UuidChunkIdGenerator implements ChunkIdGenerator {

    @Override
    public String nextId() {
        return UUID.randomUUID().toString();
    }
}
