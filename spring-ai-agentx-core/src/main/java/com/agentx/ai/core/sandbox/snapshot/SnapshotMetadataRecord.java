package com.agentx.ai.core.sandbox.snapshot;

import com.agentx.ai.core.sandbox.IsolationScope;

import java.time.Instant;

/**
 * 快照元数据记录。
 *
 * <p>对应 {@code agentx_sandbox_snapshot} 表中的一行，描述某个 scope key
 * 对应的最新快照信息。不管底层 tar 包存本地磁盘还是 MinIO，元数据都写这张表。
 *
 * @param scopeKey       会话级或用户级标识
 * @param isolationScope 隔离级别
 * @param containerName  容器名
 * @param snapshotSize   tar 文件字节数
 * @param createdAt      创建时间
 *
 * @author bigchui
 */
public record SnapshotMetadataRecord(
        String scopeKey,
        IsolationScope isolationScope,
        String containerName,
        Long snapshotSize,
        Instant createdAt
) {
}
