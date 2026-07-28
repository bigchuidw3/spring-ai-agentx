package com.agentx.ai.core.interrupt;

import com.agentx.ai.core.model.PauseState;
import com.agentx.ai.core.model.PendingToolCall;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.utils.MessageJsonSerializer;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 JDBC 的 {@link PauseStateStore} 实现，将完整 {@link PauseState} 持久化到
 * {@code agentx_pause_state} 表。
 *
 * <p>与框架其他 DataSource 驱动组件（{@code TraceStore}、{@code SessionMessageStore}）
 * 共享同一数据源，天然支持多节点跨进程恢复。通过
 * {@link DataSourceStorageFactory#createPauseStateStore(javax.sql.DataSource)} 自动建表并返回实例。
 *
 * <h2>表结构</h2>
 * <pre>
 * CREATE TABLE IF NOT EXISTS agentx_pause_state (
 *     id                BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '主键',
 *     conversation_id   VARCHAR(100) NOT NULL                 COMMENT '会话ID',
 *     reason            VARCHAR(50)  NOT NULL                 COMMENT '暂停原因',
 *     safe_point        VARCHAR(50)  DEFAULT NULL             COMMENT '中断安全点',
 *     current_round     INT          NOT NULL                 COMMENT '中断时已完成的轮次',
 *     interrupted_at    TIMESTAMP    NOT NULL                 COMMENT '中断时间',
 *     expires_at        TIMESTAMP    DEFAULT NULL             COMMENT '过期时间',
 *     snapshot_json     LONGTEXT     NOT NULL                 COMMENT '完整 PauseState JSON',
 *     PRIMARY KEY (id),
 *     UNIQUE KEY uk_conversation_id (conversation_id),
 *     INDEX idx_pause_state_expires (expires_at)
 * ) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AgentX 暂停现场快照表'
 * </pre>
 *
 * @author bigchui
 */
public class JdbcPauseStateStore implements PauseStateStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcPauseStateStore.class);

    static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS agentx_pause_state (
                id                BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '主键',
                conversation_id   VARCHAR(100) NOT NULL                 COMMENT '会话ID',
                reason            VARCHAR(50)  NOT NULL                 COMMENT '暂停原因',
                safe_point        VARCHAR(50)  DEFAULT NULL             COMMENT '中断安全点',
                current_round     INT          NOT NULL                 COMMENT '中断时已完成的轮次',
                interrupted_at    TIMESTAMP    NOT NULL                 COMMENT '中断时间',
                expires_at        TIMESTAMP    DEFAULT NULL             COMMENT '过期时间',
                snapshot_json     LONGTEXT     NOT NULL                 COMMENT '完整 PauseState JSON',
                PRIMARY KEY (id),
                UNIQUE KEY uk_conversation_id (conversation_id),
                INDEX idx_pause_state_expires (expires_at)
            ) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AgentX 暂停现场快照表'
            """;

    /** 默认 TTL：7 天（毫秒） */
    static final long DEFAULT_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000;

    private static final String UPSERT_SQL = """
            INSERT INTO agentx_pause_state
                (conversation_id, reason, safe_point, current_round, interrupted_at, expires_at, snapshot_json)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                reason        = VALUES(reason),
                safe_point    = VALUES(safe_point),
                current_round = VALUES(current_round),
                interrupted_at= VALUES(interrupted_at),
                expires_at    = VALUES(expires_at),
                snapshot_json = VALUES(snapshot_json)
            """;

    private static final String SELECT_SQL = """
            SELECT snapshot_json FROM agentx_pause_state WHERE conversation_id = ?
            """;

    private static final String EXISTS_SQL = """
            SELECT 1 FROM agentx_pause_state WHERE conversation_id = ?
            """;

    private static final String DELETE_SQL = """
            DELETE FROM agentx_pause_state WHERE conversation_id = ?
            """;

    private static final String DELETE_EXPIRED_SQL = """
            DELETE FROM agentx_pause_state WHERE expires_at IS NOT NULL AND expires_at < NOW()
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

    private final JdbcTemplate jdbcTemplate;
    private final long ttlMillis;
    private volatile boolean initialized;

    public JdbcPauseStateStore(DataSource dataSource) {
        this(dataSource, DEFAULT_TTL_MILLIS);
    }

    public JdbcPauseStateStore(DataSource dataSource, long ttlMillis) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.ttlMillis = ttlMillis;
    }

    /** 自动建表（幂等），工厂方法在构造后调用。 */
    public void initialize() {
        if (initialized) return;
        synchronized (this) {
            if (initialized) return;
            jdbcTemplate.execute(CREATE_TABLE_SQL);
            initialized = true;
            log.info("[JdbcPauseStateStore] table agentx_pause_state ready");
        }
    }

    // ==================== PauseStateStore 实现 ====================

    @Override
    public void save(PauseState state) {
        ensureInitialized();
        String conversationId = requireConversationId(state);
        try {
            String json = toJson(state);
            String reason = state.getReason() != null ? state.getReason().name() : "HITL_TOOL_REQUEST";
            String safePoint = state.getSafePoint() != null ? state.getSafePoint().name() : null;
            long interruptedAt = state.getInterruptedAt() > 0
                    ? state.getInterruptedAt() : System.currentTimeMillis();
            Timestamp expiresAt = new Timestamp(interruptedAt + ttlMillis);

            jdbcTemplate.update(UPSERT_SQL,
                    conversationId, reason, safePoint, state.getCurrentRound(),
                    new Timestamp(interruptedAt), expiresAt, json);
            log.debug("[JdbcPauseStateStore] saved convId={} reason={}", conversationId, reason);
        } catch (Exception e) {
            log.error("[JdbcPauseStateStore] save failed: convId={}", conversationId, e);
        }
    }

    @Override
    public PauseState findByConversationId(String conversationId) {
        ensureInitialized();
        if (conversationId == null) return null;
        try {
            List<String> rows = jdbcTemplate.query(SELECT_SQL,
                    (rs, rn) -> rs.getString("snapshot_json"), conversationId);
            return rows.isEmpty() ? null : fromJson(rows.get(0));
        } catch (Exception e) {
            log.error("[JdbcPauseStateStore] load failed: convId={}", conversationId, e);
            return null;
        }
    }

    @Override
    public boolean exists(String conversationId) {
        ensureInitialized();
        if (conversationId == null) return false;
        try {
            return !jdbcTemplate.query(EXISTS_SQL, (rs, rn) -> 1, conversationId).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean delete(String conversationId) {
        ensureInitialized();
        if (conversationId == null) return false;
        return jdbcTemplate.update(DELETE_SQL, conversationId) > 0;
    }

    @Override
    public int deleteExpired() {
        ensureInitialized();
        try {
            int rows = jdbcTemplate.update(DELETE_EXPIRED_SQL);
            if (rows > 0) log.info("[JdbcPauseStateStore] cleared {} expired states", rows);
            return rows;
        } catch (Exception e) {
            log.error("[JdbcPauseStateStore] deleteExpired failed", e);
            return 0;
        }
    }

    // ==================== JSON 序列化 / 反序列化 ====================

    static String toJson(PauseState state) throws JsonProcessingException {
        return MAPPER.writeValueAsString(toMap(state));
    }

    private static Map<String, Object> toMap(PauseState state) throws JsonProcessingException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("messages", MessageJsonSerializer.toMaps(state.getMessages()));
        m.put("currentRound", state.getCurrentRound());
        m.put("pendingToolCalls", serializePendingToolCalls(state.getPendingToolCalls()));
        if (state.getParams() != null) {
            m.put("params", MAPPER.convertValue(state.getParams(), new TypeReference<Map<String, Object>>() {}));
        }
        m.put("query", state.getQuery());
        m.put("sessionId", state.getSessionId());
        m.put("totalPromptTokens", state.getTotalPromptTokens());
        m.put("totalCompletionTokens", state.getTotalCompletionTokens());
        if (state.getReason() != null) m.put("reason", state.getReason().name());
        if (state.getSafePoint() != null) m.put("safePoint", state.getSafePoint().name());
        m.put("interruptMessage", state.getInterruptMessage());
        m.put("interruptedAt", state.getInterruptedAt());
        if (state.getChildren() != null && !state.getChildren().isEmpty()) {
            List<Map<String, Object>> childrenList = new ArrayList<>();
            for (PauseState child : state.getChildren()) {
                childrenList.add(toMap(child));
            }
            m.put("children", childrenList);
        }
        return m;
    }

    static PauseState fromJson(String json) throws JsonProcessingException {
        Map<String, Object> root = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        return fromMap(root);
    }

    @SuppressWarnings("unchecked")
    private static PauseState fromMap(Map<String, Object> m) throws JsonProcessingException {
        List<Map<String, Object>> messageMaps = (List<Map<String, Object>>) m.get("messages");
        List<Message> messages = messageMaps != null && !messageMaps.isEmpty()
                ? MessageJsonSerializer.fromMaps(messageMaps)
                : Collections.emptyList();

        PauseState.Builder b = PauseState.builder()
                .messages(messages)
                .currentRound(intVal(m, "currentRound"))
                .query(strVal(m, "query"))
                .sessionId(longVal(m, "sessionId"))
                .totalPromptTokens(longVal(m, "totalPromptTokens"))
                .totalCompletionTokens(longVal(m, "totalCompletionTokens"))
                .interruptMessage(strVal(m, "interruptMessage"))
                .interruptedAt(longVal(m, "interruptedAt"));

        // 枚举
        String reasonStr = strVal(m, "reason");
        if (reasonStr != null) {
            try { b.reason(PauseReason.valueOf(reasonStr)); }
            catch (IllegalArgumentException e) { /* ignore */ }
        }
        String safeStr = strVal(m, "safePoint");
        if (safeStr != null) {
            try { b.safePoint(SafePoint.valueOf(safeStr)); }
            catch (IllegalArgumentException e) { /* ignore */ }
        }

        // pendingToolCalls
        List<Map<String, Object>> ptcs = (List<Map<String, Object>>) m.get("pendingToolCalls");
        if (ptcs != null && !ptcs.isEmpty()) {
            List<PendingToolCall> list = new ArrayList<>();
            for (Map<String, Object> ptc : ptcs) {
                list.add(new PendingToolCall(
                        strVal(ptc, "id"), strVal(ptc, "name"), strVal(ptc, "arguments")));
            }
            b.pendingToolCalls(list);
        }

        // params
        Map<String, Object> paramsMap = (Map<String, Object>) m.get("params");
        if (paramsMap != null) {
            b.params(MAPPER.convertValue(paramsMap, RunnableParams.class));
        }

        // children（递归：SubAgent PauseState，children 深度恒为 1）
        List<Map<String, Object>> childrenMaps = (List<Map<String, Object>>) m.get("children");
        if (childrenMaps != null && !childrenMaps.isEmpty()) {
            List<PauseState> children = new ArrayList<>();
            for (Map<String, Object> cm : childrenMaps) {
                children.add(fromMap(cm));
            }
            b.children(children);
        }

        return b.build();
    }

    // ==================== helpers ====================

    private void ensureInitialized() {
        if (!initialized) initialize();
    }

    private static String requireConversationId(PauseState state) {
        RunnableParams params = state.getParams();
        if (params == null || params.getConversationId() == null) {
            throw new IllegalArgumentException("PauseState.params.conversationId must not be null");
        }
        return params.getConversationId();
    }

    private static List<Map<String, Object>> serializePendingToolCalls(List<PendingToolCall> ptcs) {
        if (ptcs == null || ptcs.isEmpty()) return Collections.emptyList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (PendingToolCall ptc : ptcs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ptc.id());
            m.put("name", ptc.name());
            m.put("arguments", ptc.arguments());
            result.add(m);
        }
        return result;
    }

    private static String strVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    private static int intVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static long longVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
