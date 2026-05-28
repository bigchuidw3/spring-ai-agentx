package com.agentx.ai.core.memory.store;

import com.agentx.ai.core.memory.model.MemoryItem;
import com.agentx.ai.core.memory.model.MemoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 基于内存的记忆存储实现。
 *
 * 使用 ConcurrentHashMap 存储，适用于开发和测试环境。
 * 生产环境建议使用 {@link FileMemoryStore} 或自定义的持久化实现。
 *
 * @author bigchui
 * 
 */
public class InMemoryMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryMemoryStore.class);

    /**
     * userId -> (memoryId -> MemoryItem)
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, MemoryItem>> store = new ConcurrentHashMap<>();

    @Override
    public void save(String userId, MemoryItem item) {
        store.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                .put(item.getId(), item);
        log.debug("Saved memory: userId={}, id={}, type={}", userId, item.getId(), item.getType());
    }

    @Override
    public List<MemoryItem> findByUserId(String userId) {
        ConcurrentHashMap<String, MemoryItem> userMemories = store.get(userId);
        if (userMemories == null) {
            return List.of();
        }
        return List.copyOf(userMemories.values());
    }

    @Override
    public List<MemoryItem> findByUserIdAndType(String userId, MemoryType type) {
        return findByUserId(userId).stream()
                .filter(m -> m.getType() == type)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<MemoryItem> findById(String userId, String memoryId) {
        ConcurrentHashMap<String, MemoryItem> userMemories = store.get(userId);
        if (userMemories == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(userMemories.get(memoryId));
    }

    @Override
    public void delete(String userId, String memoryId) {
        ConcurrentHashMap<String, MemoryItem> userMemories = store.get(userId);
        if (userMemories != null) {
            userMemories.remove(memoryId);
            log.debug("Deleted memory: userId={}, id={}", userId, memoryId);
        }
    }

    @Override
    public void deleteByUserId(String userId) {
        store.remove(userId);
        log.debug("Deleted all memories for userId={}", userId);
    }
}
