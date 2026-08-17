package com.agentx.ai.core.sandbox.snapshot;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存版快照元数据存储。
 *
 * <p>无 {@code DataSource} 时的兜底实现，进程重启后元数据丢失。
 *
 * @author bigchui
 */
public class InMemorySnapshotMetadataStore implements SnapshotMetadataStore {

    private final ConcurrentMap<String, SnapshotMetadataRecord> store = new ConcurrentHashMap<>();

    @Override
    public void save(SnapshotMetadataRecord record) {
        store.put(record.scopeKey(), record);
    }

    @Override
    public Optional<SnapshotMetadataRecord> find(String scopeKey) {
        return Optional.ofNullable(store.get(scopeKey));
    }

    @Override
    public void delete(String scopeKey) {
        store.remove(scopeKey);
    }
}
