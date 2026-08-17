package com.agentx.ai.core.sandbox.snapshot;

import com.agentx.ai.core.sandbox.IsolationScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 基于 JDBC 的快照元数据持久化。
 *
 * <p>对应 {@code agentx_sandbox_snapshot} 表，每个 scope key 只保留最新一份。
 * 不管底层 tar 包存本地磁盘还是 MinIO，元数据都写这张表。
 *
 * <p>用法：
 * <pre>{@code
 * SnapshotMetadataStore store = new JdbcSnapshotMetadataStore(dataSource);
 * store.initialize();  // 建表（幂等）
 * }</pre>
 *
 * @author bigchui
 */
public class JdbcSnapshotMetadataStore implements SnapshotMetadataStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcSnapshotMetadataStore.class);

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS agentx_sandbox_snapshot (
                id              BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '主键',
                scope_key       VARCHAR(200) NOT NULL                 COMMENT 'conversationId 或 userId',
                isolation_scope VARCHAR(20)  NOT NULL                 COMMENT 'CONVERSATION 或 USER',
                container_name  VARCHAR(300) NOT NULL                 COMMENT '容器名',
                snapshot_size   BIGINT       DEFAULT NULL             COMMENT 'tar 文件字节数',
                created_at      TIMESTAMP    NOT NULL                 COMMENT '创建时间',
                PRIMARY KEY (id),
                UNIQUE KEY uk_scope_key (scope_key)
            ) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AgentX 沙箱快照元数据'
            """;

    private static final String UPSERT_SQL = """
            INSERT INTO agentx_sandbox_snapshot
                (scope_key, isolation_scope, container_name, snapshot_size, created_at)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                isolation_scope = VALUES(isolation_scope),
                container_name  = VALUES(container_name),
                snapshot_size   = VALUES(snapshot_size),
                created_at      = VALUES(created_at)
            """;

    private static final String SELECT_SQL = """
            SELECT scope_key, isolation_scope, container_name, snapshot_size, created_at
            FROM agentx_sandbox_snapshot
            WHERE scope_key = ?
            """;

    private static final String DELETE_SQL = "DELETE FROM agentx_sandbox_snapshot WHERE scope_key = ?";

    private final JdbcTemplate jdbcTemplate;
    private volatile boolean initialized = false;

    public JdbcSnapshotMetadataStore(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * 建表（幂等，线程安全）。
     */
    public void initialize() {
        if (initialized) return;
        synchronized (this) {
            if (initialized) return;
            jdbcTemplate.execute(CREATE_TABLE_SQL);
            initialized = true;
            log.info("[JdbcSnapshotMetadataStore] 表 agentx_sandbox_snapshot 就绪");
        }
    }

    private void ensureInitialized() {
        if (!initialized) {
            initialize();
        }
    }

    @Override
    public void save(SnapshotMetadataRecord record) {
        ensureInitialized();
        jdbcTemplate.update(UPSERT_SQL,
                record.scopeKey(),
                record.isolationScope().name(),
                record.containerName(),
                record.snapshotSize(),
                Timestamp.from(record.createdAt()));
    }

    @Override
    public Optional<SnapshotMetadataRecord> find(String scopeKey) {
        ensureInitialized();
        List<SnapshotMetadataRecord> results = jdbcTemplate.query(SELECT_SQL, ROW_MAPPER, scopeKey);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public void delete(String scopeKey) {
        ensureInitialized();
        jdbcTemplate.update(DELETE_SQL, scopeKey);
    }

    private static final RowMapper<SnapshotMetadataRecord> ROW_MAPPER = (rs, rowNum) -> new SnapshotMetadataRecord(
            rs.getString("scope_key"),
            IsolationScope.valueOf(rs.getString("isolation_scope")),
            rs.getString("container_name"),
            rs.getObject("snapshot_size", Long.class),
            rs.getTimestamp("created_at").toInstant()
    );
}
