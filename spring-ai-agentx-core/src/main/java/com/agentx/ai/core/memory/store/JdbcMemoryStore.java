package com.agentx.ai.core.memory.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentx.ai.core.memory.model.MemoryItem;
import com.agentx.ai.core.memory.model.MemoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 JDBC 的记忆存储实现。
 *
 * 使用关系型数据库存储记忆条目，自动建表 {@code agentx_memory}。
 * 适用于零配置模式（用户提供 DataSource，框架自动管理表结构）。
 *
 * 表结构：
 * CREATE TABLE IF NOT EXISTS agentx_memory (
 *     id            BIGINT       NOT NULL,
 *     user_id       VARCHAR(100) NOT NULL,
 *     type          VARCHAR(20)  NOT NULL,
 *     content       TEXT         NOT NULL,
 *     description   TEXT         DEFAULT NULL,
 *     metadata_json TEXT         DEFAULT NULL,
 *     created_at    TIMESTAMP    DEFAULT NULL,
 *     updated_at    TIMESTAMP    DEFAULT NULL,
 *     PRIMARY KEY (id)
 * );
 *
 * 依赖 spring-jdbc（optional），不使用 DataSource 的用户不会引入此依赖。
 *
 * @author bigchui
 * 
 */
public class JdbcMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcMemoryStore.class);

    private static final String CHECK_TABLE_SQL = """
            SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'agentx_memory'
            """;

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS agentx_memory (
                id            BIGINT       NOT NULL,
                user_id       VARCHAR(100) NOT NULL,
                type          VARCHAR(20)  NOT NULL,
                content       TEXT         NOT NULL,
                description   TEXT         DEFAULT NULL,
                metadata_json TEXT         DEFAULT NULL,
                created_at    TIMESTAMP    DEFAULT NULL,
                updated_at    TIMESTAMP    DEFAULT NULL,
                PRIMARY KEY (id)
            )
            """;

    private static final String CREATE_INDEX_SQL_1 = """
            CREATE INDEX idx_agentx_memory_user_id ON agentx_memory (user_id)
            """;

    private static final String CREATE_INDEX_SQL_2 = """
            CREATE INDEX idx_agentx_memory_user_type ON agentx_memory (user_id, type)
            """;

    private static final String INSERT_SQL = """
            INSERT INTO agentx_memory (id, user_id, type, content, description, metadata_json, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String UPDATE_SQL = """
            UPDATE agentx_memory SET type = ?, content = ?, description = ?, metadata_json = ?, updated_at = ?
            WHERE id = ? AND user_id = ?
            """;

    private static final String FIND_BY_USER_ID_SQL = """
            SELECT id, user_id, type, content, description, metadata_json, created_at, updated_at
            FROM agentx_memory WHERE user_id = ? ORDER BY created_at DESC
            """;

    private static final String FIND_BY_USER_ID_AND_TYPE_SQL = """
            SELECT id, user_id, type, content, description, metadata_json, created_at, updated_at
            FROM agentx_memory WHERE user_id = ? AND type = ? ORDER BY created_at DESC
            """;

    private static final String FIND_BY_ID_SQL = """
            SELECT id, user_id, type, content, description, metadata_json, created_at, updated_at
            FROM agentx_memory WHERE user_id = ? AND id = ?
            """;

    private static final String DELETE_SQL = """
            DELETE FROM agentx_memory WHERE user_id = ? AND id = ?
            """;

    private static final String DELETE_BY_USER_ID_SQL = """
            DELETE FROM agentx_memory WHERE user_id = ?
            """;

    private static final String SEARCH_SQL = """
            SELECT id, user_id, type, content, description, metadata_json, created_at, updated_at
            FROM agentx_memory WHERE user_id = ? AND (LOWER(content) LIKE ? OR LOWER(description) LIKE ?)
            ORDER BY created_at DESC
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private volatile boolean initialized = false;

    public JdbcMemoryStore(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 初始化表结构（建表 + 建索引）。
     * 应在构建 Agent 时调用，而非首次对话时延迟初始化。
     */
    public void initialize() {
        ensureInitialized();
    }

    /**
     * 确保表结构已初始化。
     */
    private void ensureInitialized() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    Integer count = jdbcTemplate.queryForObject(CHECK_TABLE_SQL, Integer.class);
                    if (count == null || count == 0) {
                        jdbcTemplate.execute(CREATE_TABLE_SQL);
                        jdbcTemplate.execute(CREATE_INDEX_SQL_1);
                        jdbcTemplate.execute(CREATE_INDEX_SQL_2);
                        log.info("agentx_memory table created");
                    }
                    initialized = true;
                }
            }
        }
    }

    @Override
    public void save(String userId, MemoryItem item) {
        ensureInitialized();
        String metadataJson = serializeMetadata(item.getMetadata());

        // 优先尝试 UPDATE，如果不存在则 INSERT（跨数据库兼容）
        int updated = jdbcTemplate.update(UPDATE_SQL,
                item.getType().name(),
                item.getContent(),
                item.getDescription(),
                metadataJson,
                toTimestamp(item.getUpdatedAt()),
                item.getId(),
                userId);

        if (updated == 0) {
            jdbcTemplate.update(INSERT_SQL,
                    item.getId(),
                    userId,
                    item.getType().name(),
                    item.getContent(),
                    item.getDescription(),
                    metadataJson,
                    toTimestamp(item.getCreatedAt()),
                    toTimestamp(item.getUpdatedAt()));
        }

        log.debug("Saved memory to DB: userId={}, id={}, type={}", userId, item.getId(), item.getType());
    }

    @Override
    public List<MemoryItem> findByUserId(String userId) {
        ensureInitialized();
        return jdbcTemplate.query(FIND_BY_USER_ID_SQL, memoryRowMapper(), userId);
    }

    @Override
    public List<MemoryItem> findByUserIdAndType(String userId, MemoryType type) {
        ensureInitialized();
        return jdbcTemplate.query(FIND_BY_USER_ID_AND_TYPE_SQL, memoryRowMapper(), userId, type.name());
    }

    @Override
    public Optional<MemoryItem> findById(String userId, String memoryId) {
        ensureInitialized();
        List<MemoryItem> results = jdbcTemplate.query(FIND_BY_ID_SQL, memoryRowMapper(), userId, memoryId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public void delete(String userId, String memoryId) {
        ensureInitialized();
        int rows = jdbcTemplate.update(DELETE_SQL, userId, memoryId);
        log.debug("Deleted memory from DB: userId={}, id={}, rows={}", userId, memoryId, rows);
    }

    @Override
    public void deleteByUserId(String userId) {
        ensureInitialized();
        int rows = jdbcTemplate.update(DELETE_BY_USER_ID_SQL, userId);
        log.debug("Deleted all memories from DB: userId={}, rows={}", userId, rows);
    }

    @Override
    public List<MemoryItem> search(String userId, String query, int topK) {
        ensureInitialized();
        if (query == null || query.isBlank()) {
            return findByUserId(userId).stream().limit(topK).toList();
        }
        String likePattern = "%" + query.toLowerCase() + "%";
        return jdbcTemplate.query(SEARCH_SQL, memoryRowMapper(), userId, likePattern, likePattern)
                .stream()
                .limit(topK)
                .toList();
    }

    private RowMapper<MemoryItem> memoryRowMapper() {
        return (rs, rowNum) -> mapRow(rs);
    }

    private MemoryItem mapRow(ResultSet rs) throws SQLException {
        return MemoryItem.builder()
                .id(rs.getString("id"))
                .userId(rs.getString("user_id"))
                .type(MemoryType.valueOf(rs.getString("type")))
                .content(rs.getString("content"))
                .description(rs.getString("description"))
                .metadata(deserializeMetadata(rs.getString("metadata_json")))
                .createdAt(toLocalDateTime(rs.getTimestamp("created_at")))
                .updatedAt(toLocalDateTime(rs.getTimestamp("updated_at")))
                .build();
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize metadata: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deserializeMetadata(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize metadata: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private Timestamp toTimestamp(LocalDateTime ldt) {
        return ldt != null ? Timestamp.valueOf(ldt) : null;
    }

    private LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts != null ? ts.toLocalDateTime() : null;
    }
}
