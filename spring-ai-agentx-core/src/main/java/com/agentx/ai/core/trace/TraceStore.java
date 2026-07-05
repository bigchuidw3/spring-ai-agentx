package com.agentx.ai.core.trace;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Trace 审计存储层（MySQL 专用）。
 *
 * <p>自动创建 {@code agentx_trace} 表，同步写入 trace 记录。
 * 写入失败仅打印警告日志，不影响 Agent 主流程。
 *
 * <p>表结构（MySQL）：
 * <pre>
 * CREATE TABLE agentx_trace (
 *     id                BIGINT       NOT NULL,
 *     session_id        BIGINT       NOT NULL,
 *     conversation_id   VARCHAR(100) NOT NULL,
 *     round             INT          NOT NULL,
 *     input_data        LONGTEXT     DEFAULT NULL,
 *     output_data       LONGTEXT     DEFAULT NULL,
 *     think             LONGTEXT     DEFAULT NULL,
 *     prompt_tokens     INT          DEFAULT 0,
 *     completion_tokens INT          DEFAULT 0,
 *     duration_ms       BIGINT       DEFAULT 0,
 *     success           INT          DEFAULT 1,
 *     error_message     LONGTEXT     DEFAULT NULL,
 *     created_at        TIMESTAMP    DEFAULT NULL,
 *     PRIMARY KEY (id)
 * ) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
 * </pre>
 *
 * @author bigchui
 */
public class TraceStore {

    private static final Logger log = LoggerFactory.getLogger(TraceStore.class);

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE agentx_trace (
                id                BIGINT       NOT NULL  COMMENT '主键ID',
                session_id        BIGINT       NOT NULL  COMMENT '会话记录ID（agentx_session.id）',
                conversation_id   VARCHAR(100) NOT NULL  COMMENT '会话ID',
                round             INT          NOT NULL  COMMENT '本轮ReAct循环轮次',
                input_data        LONGTEXT     DEFAULT NULL COMMENT '输入内容（prompt/消息序列JSON）',
                output_data       LONGTEXT     DEFAULT NULL COMMENT '输出内容（模型回答/工具结果）',
                think             LONGTEXT     DEFAULT NULL COMMENT '模型思考内容',
                prompt_tokens     INT          DEFAULT 0 COMMENT '提示token数',
                completion_tokens INT          DEFAULT 0 COMMENT '补全token数',
                duration_ms       BIGINT       DEFAULT 0 COMMENT '本轮耗时（毫秒）',
                success           INT          DEFAULT 1 COMMENT '是否成功：1成功 0失败',
                error_message     LONGTEXT     DEFAULT NULL COMMENT '错误信息',
                created_at        TIMESTAMP    DEFAULT NULL COMMENT '创建时间',
                PRIMARY KEY (id)
            ) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AgentX追踪审计表'
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
                    // MySQL 专用建表语句（utf8mb4 + 内联注释），表已存在时静默跳过
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
            // success 传 int 而非 boolean：SMALLINT 列在 PG/GaussDB 上 setBoolean 会抛类型错误
            jdbcTemplate.update(INSERT_SQL,
                    IdWorker.getId(), sessionId, conversationId, round,
                    inputData, outputData, think,
                    promptTokens, completionTokens, durationMs,
                    success ? 1 : 0, errorMessage);
        } catch (Exception e) {
            log.warn("[TraceStore] Failed to save trace: sessionId={}, round={}, error={}",
                    sessionId, round, e.getMessage());
        }
    }
}
