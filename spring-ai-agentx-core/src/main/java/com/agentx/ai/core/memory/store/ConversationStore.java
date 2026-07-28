package com.agentx.ai.core.memory.store;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 会话窗口存储 — agentx_conversation 表的 CRUD。
 * 每次调用一行，记录 question 与执行状态，供前端展示和历史回放。
 *
 * @author bigchui
 */
public class ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationStore.class);

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE agentx_conversation (
                id              BIGINT       NOT NULL  COMMENT '主键ID',
                conversation_id VARCHAR(100) NOT NULL  COMMENT '会话窗口ID',
                session_id      VARCHAR(100) NOT NULL  COMMENT '本次调用ID',
                user_id         VARCHAR(100) DEFAULT NULL COMMENT '用户ID',
                question        LONGTEXT     NOT NULL  COMMENT '用户提问',
                status          VARCHAR(20)  DEFAULT 'running' COMMENT '执行状态: running/completed/interrupted/error',
                created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                completed_at    TIMESTAMP    DEFAULT NULL COMMENT '完成时间',
                PRIMARY KEY (id)
            ) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AgentX会话窗口表'
            """;

    private static final String CREATE_UK_SESSION_SQL = """
            CREATE UNIQUE INDEX uk_conv_session ON agentx_conversation (session_id)
            """;

    private static final String CREATE_IDX_CONV_SQL = """
            CREATE INDEX idx_conv_id ON agentx_conversation (conversation_id)
            """;

    private static final String INSERT_SQL = """
            INSERT INTO agentx_conversation (id, conversation_id, session_id, user_id, question, status, created_at)
            VALUES (?, ?, ?, ?, ?, 'running', CURRENT_TIMESTAMP)
            """;

    private static final String UPDATE_STATUS_SQL = """
            UPDATE agentx_conversation
            SET status = ?, completed_at = CASE WHEN ? = 'completed' THEN CURRENT_TIMESTAMP ELSE completed_at END
            WHERE session_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private volatile boolean initialized = false;

    public ConversationStore(DataSource dataSource) {
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
                        log.debug("Table agentx_conversation creation skipped (may already exist): {}", e.getMessage());
                    }
                    try {
                        jdbcTemplate.execute(CREATE_UK_SESSION_SQL);
                    } catch (Exception e) {
                        log.debug("Unique index uk_conv_session skipped: {}", e.getMessage());
                    }
                    try {
                        jdbcTemplate.execute(CREATE_IDX_CONV_SQL);
                    } catch (Exception e) {
                        log.debug("Index idx_conv_id skipped: {}", e.getMessage());
                    }
                    initialized = true;
                    log.info("agentx_conversation table initialized");
                }
            }
        }
    }

    /**
     * 开局保存：调用开始时写入一行，status='running'。
     */
    public void saveStart(String conversationId, long sessionId, String userId, String question) {
        if (conversationId == null || question == null) {
            return;
        }
        ensureInitialized();
        jdbcTemplate.update(INSERT_SQL,
                IdWorker.getId(), conversationId, String.valueOf(sessionId), userId, question);
        log.debug("Saved conversation start: conversationId={}, sessionId={}", conversationId, sessionId);
    }

    /**
     * 更新终态状态。status='completed' 时同步写 completed_at。
     */
    public void updateStatus(long sessionId, String status) {
        ensureInitialized();
        jdbcTemplate.update(UPDATE_STATUS_SQL, status, status, String.valueOf(sessionId));
        log.debug("Updated conversation status: sessionId={}, status={}", sessionId, status);
    }
}
