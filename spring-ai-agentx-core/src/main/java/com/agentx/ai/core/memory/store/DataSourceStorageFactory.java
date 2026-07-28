package com.agentx.ai.core.memory.store;

import com.agentx.ai.core.interrupt.JdbcPauseStateStore;
import com.agentx.ai.core.interrupt.PauseStateStore;
import com.agentx.ai.core.trace.TraceStore;
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
 * - {@link JdbcPauseStateStore} — 暂停快照（{@code agentx_pause_state} 表）
 *
 * 所有表统一以 {@code agentx_} 前缀命名，框架自动建表。
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
     * @deprecated 主流程已切换到 {@link #createSessionMessageStore} + {@link #createConversationStore}，
     * 完整消息链存储替代 QA 对存储。保留仅供旧调用方兼容。
     */
    @Deprecated
    public static ChatMemory createChatMemory(DataSource dataSource) {
        AgentChatMemory chatMemory = new AgentChatMemory(dataSource);
        chatMemory.initialize();
        log.info("Created and initialized ChatMemory (agentx_session table)");
        return chatMemory;
    }

    /**
     * 基于 DataSource 创建 ConversationStore。
     *
     * 自动创建 {@code agentx_conversation} 表，记录每次会话调用的开局与终态。
     *
     * @param dataSource 数据源
     * @return ConversationStore 实例
     */
    public static ConversationStore createConversationStore(DataSource dataSource) {
        ConversationStore store = new ConversationStore(dataSource);
        store.initialize();
        log.info("Created and initialized ConversationStore (agentx_conversation table)");
        return store;
    }

    /**
     * 基于 DataSource 创建 SessionMessageStore。
     *
     * 自动创建 {@code agentx_session} 表（新结构：完整消息链存储）。
     * 与旧 AgentChatMemory 同表名但结构不同，迁移时需先 drop 旧表。
     *
     * @param dataSource 数据源
     * @return SessionMessageStore 实例
     */
    public static SessionMessageStore createSessionMessageStore(DataSource dataSource) {
        SessionMessageStore store = new SessionMessageStore(dataSource);
        store.initialize();
        log.info("Created and initialized SessionMessageStore (agentx_session table)");
        return store;
    }

    /**
     * 基于 DataSource 创建 TraceStore。
     *
     * 自动创建 {@code agentx_trace} 表。
     *
     * @param dataSource 数据源
     * @return TraceStore 实例
     */
    public static TraceStore createTraceStore(DataSource dataSource) {
        TraceStore traceStore = new TraceStore(dataSource);
        traceStore.initialize();
        log.info("Created and initialized TraceStore (agentx_trace table)");
        return traceStore;
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

    /**
     * 基于 DataSource 创建 PauseStateStore。
     *
     * 自动创建 {@code agentx_pause_state} 表。
     *
     * @param dataSource 数据源
     * @return PauseStateStore 实例
     */
    public static PauseStateStore createPauseStateStore(DataSource dataSource) {
        JdbcPauseStateStore store = new JdbcPauseStateStore(dataSource);
        store.initialize();
        log.info("Created and initialized PauseStateStore (agentx_pause_state table)");
        return store;
    }
}
