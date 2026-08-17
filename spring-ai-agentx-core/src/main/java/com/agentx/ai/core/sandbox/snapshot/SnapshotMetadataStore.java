package com.agentx.ai.core.sandbox.snapshot;

import java.util.Optional;

/**
 * 快照元数据持久化接口。
 *
 * <p>存储 scope key → 快照元数据的映射。不管底层 tar 包存本地磁盘还是 MinIO，
 * 元数据都写这层，保证行为一致、可查询、可运维管理。
 *
 * <p>实现方：
 * <ul>
 *   <li>{@code JdbcSnapshotMetadataStore} — JDBC 持久化（agentx_sandbox_snapshot 表，生产推荐）</li>
 *   <li>{@code InMemorySnapshotMetadataStore} — 内存实现（无 DataSource 时兜底，进程重启丢失）</li>
 * </ul>
 *
 * <p>每个 scope key 只保留最新一份（覆盖写）。
 *
 * @author bigchui
 */
public interface SnapshotMetadataStore {

    /**
     * 保存快照元数据记录（覆盖写：同一 scope key 只保留最新）。
     *
     * @param record 快照记录
     */
    void save(SnapshotMetadataRecord record);

    /**
     * 查找 scope key 对应的快照记录。
     *
     * @param scopeKey 会话级或用户级标识
     * @return 快照记录；不存在返回 {@link Optional#empty()}
     */
    Optional<SnapshotMetadataRecord> find(String scopeKey);

    /**
     * 删除 scope key 对应的快照记录。
     *
     * @param scopeKey 会话级或用户级标识
     */
    void delete(String scopeKey);
}
