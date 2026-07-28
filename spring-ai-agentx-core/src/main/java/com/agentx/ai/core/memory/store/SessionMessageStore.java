package com.agentx.ai.core.memory.store;

import com.agentx.ai.core.utils.MessageJsonSerializer;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 会话消息链存储 — agentx_session 表的 CRUD。
 * 每条消息一行，按 conversation_id + state_key 聚合，item_index 保留顺序。
 * 终态时批量追加本次调用新增的消息，ReAct 循环中不触碰 DB。
 *
 * @author bigchui
 */
public class SessionMessageStore {

    private static final Logger log = LoggerFactory.getLogger(SessionMessageStore.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE agentx_session (
                id              BIGINT       NOT NULL  COMMENT '主键ID',
                conversation_id VARCHAR(100) NOT NULL  COMMENT '会话窗口ID',
                session_id      VARCHAR(100) NOT NULL  COMMENT '本次调用ID',
                state_key       VARCHAR(255) NOT NULL  COMMENT '状态键: original_messages / working_messages / offload_context',
                item_index      INT          NOT NULL DEFAULT 0 COMMENT '消息在状态键内的序号',
                state_data      LONGTEXT     NOT NULL  COMMENT '消息JSON（MessageJsonSerializer 序列化）',
                created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                updated_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                PRIMARY KEY (id)
            ) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AgentX会话消息链表'
            """;

    private static final String CREATE_IDX_SESSION_SQL = """
            CREATE INDEX idx_session_state ON agentx_session (session_id, state_key)
            """;

    private static final String CREATE_IDX_CONV_SQL = """
            CREATE INDEX idx_conv_state ON agentx_session (conversation_id, state_key)
            """;

    private static final String INSERT_SQL = """
            INSERT INTO agentx_session (id, conversation_id, session_id, state_key, item_index, state_data, created_at)
            VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """;

    private static final String SELECT_SQL = """
            SELECT state_data FROM agentx_session
            WHERE conversation_id = ? AND state_key = ?
            ORDER BY item_index ASC, id ASC
            """;

    private static final String MAX_INDEX_SQL = """
            SELECT COALESCE(MAX(item_index), -1) FROM agentx_session
            WHERE conversation_id = ? AND state_key = ?
            """;

    private static final String DELETE_BY_CONV_KEY_SQL = """
            DELETE FROM agentx_session
            WHERE conversation_id = ? AND state_key = ?
            """;

    private static final String SELECT_OFFLOAD_SQL = """
            SELECT state_data FROM agentx_session
            WHERE conversation_id = ? AND state_key = 'offload_context'
            ORDER BY item_index ASC, id ASC
            """;

    private static final String SELECT_OFFLOAD_BY_UUID_SQL = """
            SELECT state_data FROM agentx_session
            WHERE state_key = 'offload_context' AND state_data LIKE ?
            LIMIT 1
            """;

    private final JdbcTemplate jdbcTemplate;
    private volatile boolean initialized = false;

    public SessionMessageStore(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    public void initialize() {
        ensureInitialized();
    }

    private void ensureInitialized() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    try {
                        jdbcTemplate.execute(CREATE_TABLE_SQL);
                    } catch (Exception e) {
                        log.debug("Table agentx_session creation skipped (may already exist): {}", e.getMessage());
                    }
                    try {
                        jdbcTemplate.execute(CREATE_IDX_SESSION_SQL);
                    } catch (Exception e) {
                        log.debug("Index idx_session_state skipped: {}", e.getMessage());
                    }
                    try {
                        jdbcTemplate.execute(CREATE_IDX_CONV_SQL);
                    } catch (Exception e) {
                        log.debug("Index idx_conv_state skipped: {}", e.getMessage());
                    }
                    initialized = true;
                    log.info("agentx_session table initialized");
                }
            }
        }
    }

    /**
     * 加载某会话窗口指定状态键的全部消息，按 item_index 顺序合并。
     * 用于多轮对话加载历史上下文。
     */
    public List<Message> getMessages(String conversationId, String stateKey) {
        if (conversationId == null || stateKey == null) {
            return new ArrayList<>();
        }
        ensureInitialized();
        List<String> jsonList = jdbcTemplate.queryForList(
                SELECT_SQL, String.class, conversationId, stateKey);

        List<Message> all = new ArrayList<>(jsonList.size() * 2);
        for (String json : jsonList) {
            if (json != null && !json.isBlank()) {
                all.addAll(MessageJsonSerializer.fromJson(json));
            }
        }
        return all;
    }

    /**
     * 批量追加消息。item_index 从当前最大值 +1 起递增。
     * 终态时调用：一次调用新增的消息一次性写入，避免 ReAct 循环中频繁 DB I/O。
     */
    public void appendMessages(String conversationId, long sessionId,
                               String stateKey, List<Message> messages) {
        if (conversationId == null || stateKey == null || messages == null || messages.isEmpty()) {
            return;
        }
        ensureInitialized();

        Integer maxIndex = jdbcTemplate.queryForObject(MAX_INDEX_SQL, Integer.class, conversationId, stateKey);
        int startIndex = (maxIndex == null ? -1 : maxIndex) + 1;

        String sessionIdStr = String.valueOf(sessionId);
        List<Object[]> batchArgs = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            String data = MessageJsonSerializer.toJson(List.of(messages.get(i)));
            batchArgs.add(new Object[]{
                    IdWorker.getId(), conversationId, sessionIdStr, stateKey, startIndex + i, data
            });
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, batchArgs);
        log.debug("Appended {} messages: conversationId={}, sessionId={}, stateKey={}, startIndex={}",
                messages.size(), conversationId, sessionId, stateKey, startIndex);
    }

    /**
     * 覆盖写：先删除指定 conversationId+stateKey 的全部行，再批量插入。
     * 用于 working_messages 等需要随压缩演进的视图状态。
     */
    public void replaceMessages(String conversationId, long sessionId,
                                String stateKey, List<Message> messages) {
        if (conversationId == null || stateKey == null) {
            return;
        }
        ensureInitialized();
        jdbcTemplate.update(DELETE_BY_CONV_KEY_SQL, conversationId, stateKey);
        appendMessages(conversationId, sessionId, stateKey, messages);
        log.debug("Replaced state: conversationId={}, stateKey={}, rows={}",
                conversationId, stateKey, messages != null ? messages.size() : 0);
    }

    /**
     * 追加单条 offload 消息到 offload_context 状态键。
     * state_data 格式：{"uuid":"...","message":[{...}]}
     * 用于保存各压缩层替换下来的原文，配合 context_reload 工具按需取回。
     */
    public void appendOffload(String conversationId, long sessionId,
                              String uuid, Message message) {
        if (message == null) {
            return;
        }
        appendOffloadMessages(conversationId, sessionId, uuid, List.of(message));
    }

    public void appendOffloadMessages(String conversationId, long sessionId,
                                      String uuid, List<Message> messages) {
        if (conversationId == null || uuid == null || messages == null || messages.isEmpty()) {
            return;
        }
        ensureInitialized();
        Integer maxIndex = jdbcTemplate.queryForObject(MAX_INDEX_SQL, Integer.class,
                conversationId, "offload_context");
        int nextIndex = (maxIndex == null ? -1 : maxIndex) + 1;

        String data;
        try {
            data = objectMapper.writeValueAsString(Map.of(
                    "uuid", uuid,
                    "message", MessageJsonSerializer.toMaps(messages)
            ));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize offload payload", e);
        }
        jdbcTemplate.update(INSERT_SQL,
                IdWorker.getId(), conversationId, String.valueOf(sessionId),
                "offload_context", nextIndex, data);
        log.debug("Offloaded messages: conversationId={}, uuid={}, itemIndex={}, count={}",
                conversationId, uuid, nextIndex, messages.size());
    }

    /**
     * 按 UUID 从 offload_context 取回原文。不存在返回空列表。
     */
    public List<Message> getOffloaded(String conversationId, String uuid) {
        if (conversationId == null || uuid == null) {
            return List.of();
        }
        ensureInitialized();
        List<String> jsonList = jdbcTemplate.queryForList(
                SELECT_OFFLOAD_SQL, String.class, conversationId);
        return findUuidInRows(jsonList, uuid);
    }

    /**
     * 仅按 UUID 全局取回原文（不限定 conversationId）。
     * 供 context_reload 工具使用：LLM 调用时只知道 uuid。
     * uuid 必须为标准 UUID 格式，避免 SQL 注入。
     */
    public List<Message> getOffloadedByUuid(String uuid) {
        if (uuid == null || !isStandardUuid(uuid)) {
            return List.of();
        }
        ensureInitialized();
        String pattern = "%\"uuid\":\"" + uuid + "\"%";
        List<String> jsonList = jdbcTemplate.queryForList(
                SELECT_OFFLOAD_BY_UUID_SQL, String.class, pattern);
        return findUuidInRows(jsonList, uuid);
    }

    private List<Message> findUuidInRows(List<String> jsonList, String uuid) {
        for (String json : jsonList) {
            if (json == null || json.isBlank()) continue;
            if (!json.contains("\"uuid\":\"" + uuid + "\"")) continue;
            try {
                Map<String, Object> row = objectMapper.readValue(json, new TypeReference<>() {});
                Object rawMessages = row.get("message");
                if (!(rawMessages instanceof List<?> list) || list.isEmpty()) {
                    continue;
                }
                String msgJson = objectMapper.writeValueAsString(list);
                List<Message> parsed = MessageJsonSerializer.fromJson(msgJson);
                if (!parsed.isEmpty()) {
                    return parsed;
                }
            } catch (Exception e) {
                log.warn("Failed to parse offloaded message uuid={}: {}", uuid, e.getMessage());
            }
        }
        return List.of();
    }

    private static boolean isStandardUuid(String uuid) {
        if (uuid.length() < 8 || uuid.length() > 80) return false;
        for (int i = 0; i < uuid.length(); i++) {
            char c = uuid.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == '-')) {
                return false;
            }
        }
        return true;
    }
}
