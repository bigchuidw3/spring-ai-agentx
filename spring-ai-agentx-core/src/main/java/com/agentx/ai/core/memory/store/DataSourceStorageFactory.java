package com.agentx.ai.core.memory.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;

import javax.sql.DataSource;

/**
 * DataSource 模式的存储工厂。
 *
 * 根据 DataSource 自动创建：
 * - {@link AgentChatMemory} — 会话记忆（{@code agentx_session} 表）
 * - {@link JdbcMemoryStore} — 长期记忆（{@code agentx_memory} 表）
 *
 * 两张表统一以 {@code agentx_} 前缀命名，框架自动建表。
 *
 * @author bigchui
 * 
 */
public class DataSourceStorageFactory {

    private static final Logger log = LoggerFactory.getLogger(DataSourceStorageFactory.class);

    /**
     * 基于 DataSource 创建 ChatMemory。
     *
     * 自动创建 {@code agentx_session} 表。
     *
     * @param dataSource 数据源
     * @return ChatMemory 实例
     */
    public static ChatMemory createChatMemory(DataSource dataSource) {
        AgentChatMemory chatMemory = new AgentChatMemory(dataSource);
        chatMemory.initialize();
        log.info("Created and initialized ChatMemory (agentx_session table)");
        return chatMemory;
    }

    /**
     * 基于 DataSource 创建 MemoryStore。
     *
     * 自动创建 {@code agentx_memory} 表。
     *
     * @param dataSource 数据源
     * @return MemoryStore 实例
     */
    public static MemoryStore createMemoryStore(DataSource dataSource) {
        JdbcMemoryStore store = new JdbcMemoryStore(dataSource);
        store.initialize();
        log.info("Created and initialized MemoryStore (agentx_memory table)");
        return store;
    }
}
