package com.agentx.ai.core.memory.store;

import com.agentx.ai.core.memory.model.MemoryItem;
import com.agentx.ai.core.memory.model.MemoryType;

import java.util.List;
import java.util.Optional;

/**
 * 长期记忆存储接口（SPI）。
 *
 * 提供按 userId 维度的记忆存储和检索能力。
 * 框架内置 InMemoryMemoryStore 和 FileMemoryStore，
 * 用户可自行实现基于数据库、向量数据库等的存储。
 *
 * 与 Spring AI 的 {@code ChatMemoryRepository} 定位不同：
 * - ChatMemoryRepository - 存储原始对话消息（Message），按 conversationId 组织
 * - MemoryStore - 存储提炼后的结构化记忆（MemoryItem），按 userId 组织，跨会话持久
 *
 * @author bigchui
 * 
 */
public interface MemoryStore {

    /**
     * 保存一条记忆。如果 id 已存在则更新。
     *
     * @param userId 用户标识
     * @param item   记忆条目
     */
    void save(String userId, MemoryItem item);

    /**
     * 加载用户的所有记忆。
     *
     * @param userId 用户标识
     * @return 记忆条目列表
     */
    List<MemoryItem> findByUserId(String userId);

    /**
     * 按类型加载用户记忆。
     *
     * @param userId 用户标识
     * @param type   记忆类型
     * @return 符合类型的记忆条目列表
     */
    List<MemoryItem> findByUserIdAndType(String userId, MemoryType type);

    /**
     * 按ID查找记忆。
     *
     * @param userId   用户标识
     * @param memoryId 记忆ID
     * @return 记忆条目
     */
    Optional<MemoryItem> findById(String userId, String memoryId);

    /**
     * 删除一条记忆。
     *
     * @param userId   用户标识
     * @param memoryId 记忆ID
     */
    void delete(String userId, String memoryId);

    /**
     * 语义搜索记忆。
     *
     * 默认实现为关键词匹配，VectorBacked 实现可覆盖为向量语义检索。
     *
     * @param userId 用户标识
     * @param query  搜索查询
     * @param topK   返回条数上限
     * @return 相关记忆条目列表
     */
    default List<MemoryItem> search(String userId, String query, int topK) {
        if (query == null || query.isBlank()) {
            return findByUserId(userId).stream().limit(topK).toList();
        }
        String lowerQuery = query.toLowerCase();
        return findByUserId(userId).stream()
                .filter(m -> (m.getContent() != null && m.getContent().toLowerCase().contains(lowerQuery))
                        || (m.getDescription() != null && m.getDescription().toLowerCase().contains(lowerQuery)))
                .limit(topK)
                .toList();
    }

    /**
     * 删除用户的所有记忆。
     *
     * @param userId 用户标识
     */
    default void deleteByUserId(String userId) {
        findByUserId(userId).forEach(m -> delete(userId, m.getId()));
    }
}
