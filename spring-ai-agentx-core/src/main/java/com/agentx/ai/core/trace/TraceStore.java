package com.agentx.ai.core.trace;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Trace 审计存储层。
 *
 * <p>自动创建 {@code agentx_trace} 表，同步写入 trace 记录。
 * 写入失败仅打印警告日志，不影响 Agent 主流程。
 *
 * @author bigchui
 */
public class TraceStore {

    private static final Logger log = LoggerFactory.getLogger(TraceStore.class);

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE agentx_trace (
                id                BIGINT       NOT NULL,
                session_id        BIGINT       NOT NULL,
                conversation_id   VARCHAR(100) NOT NULL,
                round             INT          NOT NULL,
                input_data        LONGTEXT     DEFAULT NULL,
                output_data       LONGTEXT     DEFAULT NULL,
                think             TEXT         DEFAULT NULL,
                prompt_tokens     INT          DEFAULT 0,
                completion_tokens INT          DEFAULT 0,
                duration_ms       BIGINT       DEFAULT 0,
                success           TINYINT(1)   DEFAULT 1,
                error_message     TEXT         DEFAULT NULL,
                created_at        TIMESTAMP    DEFAULT NULL,
                PRIMARY KEY (id)
            )
            """;

    private static final String CREATE_INDEX_SESSION_SQL = """
            CREATE INDEX idx_agentx_trace_session ON agentx_trace (session_id)
            """;

    private static final String CREATE_INDEX_CONV_SQL = """
            CREATE INDEX idx_agentx_trace_conv ON agentx_trace (conversation_id)
            """;

    private static final String INSERT_SQL = """
            INSERT INTO agentx_trace (id, session_id, conversation_id, round,
                input_data, output_data, think,
                prompt_tokens, completion_tokens, duration_ms, success, error_message, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """;

    private final JdbcTemplate jdbcTemplate;
    private volatile boolean initialized = false;

    public TraceStore(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    public void initialize() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    try {
                        jdbcTemplate.execute(CREATE_TABLE_SQL);
                    } catch (Exception e) {
                        log.debug("Table creation skipped (may already exist): {}", e.getMessage());
                    }
                    try {
                        jdbcTemplate.execute(CREATE_INDEX_SESSION_SQL);
                        jdbcTemplate.execute(CREATE_INDEX_CONV_SQL);
                    } catch (Exception e) {
                        log.debug("Index creation skipped: {}", e.getMessage());
                    }
                    initialized = true;
                    log.info("agentx_trace table initialized");
                }
            }
        }
    }

    /**
     * 同步写入一条 trace 记录。失败仅打日志，不抛异常。
     */
    public void save(long sessionId, String conversationId, int round,
                     String inputData, String outputData, String think,
                     int promptTokens, int completionTokens,
                     long durationMs, boolean success, String errorMessage) {
        try {
            jdbcTemplate.update(INSERT_SQL,
                    IdWorker.getId(), sessionId, conversationId, round,
                    inputData, outputData, think,
                    promptTokens, completionTokens, durationMs,
                    success, errorMessage);
        } catch (Exception e) {
            log.warn("[TraceStore] Failed to save trace: sessionId={}, round={}, error={}",
                    sessionId, round, e.getMessage());
        }
    }
}
