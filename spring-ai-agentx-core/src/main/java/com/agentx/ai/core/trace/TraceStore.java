package com.agentx.ai.core.trace;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;

/**
 * Trace 审计存储层（MySQL 专用）— 统一 Span 模型。
 *
 * <p>同一张 {@code agentx_trace} 表存储两类 Span：
 * <ul>
 *   <li>{@code LLM}：一次 ReAct 轮次的模型调用（消息序列 / 回答 / 思考 / token / 耗时）</li>
 *   <li>{@code TOOL}：一次工具执行（工具名 / 调用ID / 入参 / 结果 / 成败 / 耗时）</li>
 * </ul>
 * 工具 Span 的 round 为发起该工具调用的 LLM 轮次；{@code ORDER BY id}（雪花ID单调）
 * 可线性还原 LLM轮 → 工具 → LLM轮 的执行序列。
 *
 * <p>{@code created_at} 记录<b>事件发生时刻</b>（Java 侧在 trace()/traceTool() 调用时捕获），
 * 而非数据库写入时刻；列精度为毫秒（TIMESTAMP(3)）。
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
 *     span_type         VARCHAR(8)   NOT NULL DEFAULT 'LLM',
 *     tool_name         VARCHAR(128) DEFAULT NULL,
 *     tool_call_id      VARCHAR(100) DEFAULT NULL,
 *     input_data        LONGTEXT     DEFAULT NULL,
 *     output_data       LONGTEXT     DEFAULT NULL,
 *     think             LONGTEXT     DEFAULT NULL,
 *     prompt_tokens     INT          DEFAULT 0,
 *     completion_tokens INT          DEFAULT 0,
 *     duration_ms       BIGINT       DEFAULT 0,
 *     success           INT          DEFAULT 1,
 *     error_message     LONGTEXT     DEFAULT NULL,
 *     created_at        TIMESTAMP(3) DEFAULT NULL,
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
                user_id           VARCHAR(100) DEFAULT NULL COMMENT '用户ID',
                round             INT          NOT NULL  COMMENT '本轮ReAct循环轮次；工具Span为发起调用的LLM轮次',
                span_type         VARCHAR(8)   NOT NULL DEFAULT 'LLM' COMMENT 'Span类型：LLM模型调用 | TOOL工具执行',
                tool_name         VARCHAR(128) DEFAULT NULL COMMENT '工具名（仅TOOL）',
                tool_call_id      VARCHAR(100) DEFAULT NULL COMMENT '模型tool_calls的调用ID（仅TOOL）',
                input_data        LONGTEXT     DEFAULT NULL COMMENT 'LLM:输入消息序列JSON / TOOL:工具入参JSON',
                output_data       LONGTEXT     DEFAULT NULL COMMENT 'LLM:模型回答或tool_calls / TOOL:工具结果',
                think             LONGTEXT     DEFAULT NULL COMMENT '模型思考内容（仅LLM）',
                prompt_tokens     INT          DEFAULT 0 COMMENT '提示token数（仅LLM）',
                completion_tokens INT          DEFAULT 0 COMMENT '补全token数（仅LLM）',
                duration_ms       BIGINT       DEFAULT 0 COMMENT '本Span耗时（毫秒）',
                success           INT          DEFAULT 1 COMMENT '是否成功：1成功 0失败',
                error_message     LONGTEXT     DEFAULT NULL COMMENT '错误信息',
                created_at        TIMESTAMP(3) DEFAULT NULL COMMENT '事件发生时刻（Java侧写入，毫秒精度）',
                compact_id        BIGINT       DEFAULT NULL COMMENT '所属COMPACT span id（仅压缩摘要LLM使用）',
                PRIMARY KEY (id)
            ) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AgentX统一Span审计表'
            """;

    private static final String CREATE_INDEX_SESSION_SQL = """
            CREATE INDEX idx_agentx_trace_session ON agentx_trace (session_id)
            """;

    private static final String CREATE_INDEX_CONV_SQL = """
            CREATE INDEX idx_agentx_trace_conv ON agentx_trace (conversation_id)
            """;

    private static final String INSERT_LLM_SQL = """
            INSERT INTO agentx_trace (id, session_id, conversation_id, user_id, round,
                span_type, tool_name, tool_call_id,
                input_data, output_data, think,
                prompt_tokens, completion_tokens, duration_ms, success, error_message, created_at, compact_id)
            VALUES (?, ?, ?, ?, ?, 'LLM', NULL, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_TOOL_SQL = """
            INSERT INTO agentx_trace (id, session_id, conversation_id, user_id, round,
                span_type, tool_name, tool_call_id,
                input_data, output_data, think,
                prompt_tokens, completion_tokens, duration_ms, success, error_message, created_at)
            VALUES (?, ?, ?, ?, ?, 'TOOL', ?, ?, ?, ?, NULL, 0, 0, ?, ?, ?, ?)
            """;

    private static final String INSERT_COMPACT_SQL = """
            INSERT INTO agentx_trace (id, session_id, conversation_id, user_id, round,
                span_type, tool_name, tool_call_id,
                input_data, output_data, think,
                prompt_tokens, completion_tokens, duration_ms, success, error_message, created_at, compact_id)
            VALUES (?, ?, ?, ?, ?, 'COMPACT', ?, NULL, NULL, ?, NULL, 0, 0, ?, 1, NULL, ?, NULL)
            """;

    private static final String COUNT_TOOLS_SQL = """
            SELECT COUNT(*) FROM agentx_trace WHERE session_id = ? AND span_type = 'TOOL'
            """;

    private static final String COUNT_TOOL_FAILURES_SQL = """
            SELECT COUNT(*) FROM agentx_trace WHERE session_id = ? AND span_type = 'TOOL' AND success = 0
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
     * 同步写入一条 LLM Span。失败仅打日志，不抛异常。
     *
     * @param eventTime 事件发生时刻（由调用方捕获，而非入库时刻）
     */
    public void save(long sessionId, String conversationId, String userId, int round,
                     String inputData, String outputData, String think,
                     int promptTokens, int completionTokens,
                     long durationMs, boolean success, String errorMessage, Timestamp eventTime,
                     Long compactId) {
        try {
            // success 传 int 而非 boolean：SMALLINT 列在 PG/GaussDB 上 setBoolean 会抛类型错误
            jdbcTemplate.update(INSERT_LLM_SQL,
                    IdWorker.getId(), sessionId, conversationId, userId, round,
                    inputData, outputData, think,
                    promptTokens, completionTokens, durationMs,
                    success ? 1 : 0, errorMessage, eventTime, compactId);
        } catch (Exception e) {
            log.warn("[TraceStore] Failed to save LLM span: sessionId={}, round={}, error={}",
                    sessionId, round, e.getMessage());
        }
    }

    /**
     * 同步写入一条工具执行 Span。失败仅打日志，不抛异常。
     *
     * @param round     发起该工具调用的 LLM 轮次
     * @param eventTime 事件发生时刻（由调用方捕获）
     */
    public void saveTool(long sessionId, String conversationId, String userId, int round,
                         String toolName, String toolCallId,
                         String arguments, String result,
                         boolean success, long durationMs, String errorMessage, Timestamp eventTime) {
        try {
            jdbcTemplate.update(INSERT_TOOL_SQL,
                    IdWorker.getId(), sessionId, conversationId, userId, round,
                    toolName, toolCallId,
                    arguments, result,
                    durationMs,
                    success ? 1 : 0, errorMessage, eventTime);
        } catch (Exception e) {
            log.warn("[TraceStore] Failed to save tool span: sessionId={}, round={}, tool={}, error={}",
                    sessionId, round, toolName, e.getMessage());
        }
    }

    /**
     * 同步写入一条上下文压缩 Span（COMPACT）。失败仅打日志，不抛异常。
     *
     * @param strategy    压缩策略名（L1-L6）
     * @param summaryJson 压缩详情 JSON（前后 token/消息数等）
     */
    public long saveCompaction(long sessionId, String conversationId, String userId, int round,
                               String strategy, String summaryJson,
                               long durationMs, Timestamp eventTime) {
        long id = IdWorker.getId();
        try {
            jdbcTemplate.update(INSERT_COMPACT_SQL,
                    id, sessionId, conversationId, userId, round,
                    strategy, summaryJson,
                    durationMs, eventTime);
        } catch (Exception e) {
            log.warn("[TraceStore] Failed to save compaction span: sessionId={}, round={}, strategy={}, error={}",
                    sessionId, round, strategy, e.getMessage());
        }
        return id;
    }

    /**
     * 统计本次调用的工具执行总次数（按 session_id 限定，跨 HITL 暂停/恢复累计准确）。
     */
    public long countTools(long sessionId) {
        try {
            Long count = jdbcTemplate.queryForObject(COUNT_TOOLS_SQL, Long.class, sessionId);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.warn("[TraceStore] Failed to count tools: sessionId={}, error={}", sessionId, e.getMessage());
            return 0;
        }
    }

    /**
     * 统计本次调用失败的工具执行次数。
     */
    public long countToolFailures(long sessionId) {
        try {
            Long count = jdbcTemplate.queryForObject(COUNT_TOOL_FAILURES_SQL, Long.class, sessionId);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.warn("[TraceStore] Failed to count tool failures: sessionId={}, error={}", sessionId, e.getMessage());
            return 0;
        }
    }
}
