package com.agentx.ai.core.memory.store;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 会话窗口存储 — agentx_conversation 表的 CRUD。
 * 每次调用一行，记录 question、执行状态与运行观测汇总（轮次/工具/Token/耗时/模型），
 * 供调用列表、统计看板与前端历史回放直接消费。
 *
 * <p>观测汇总列在终态时由 {@link #updateTerminal} 一并写入；
 * duration_ms 采用累加语义（COALESCE + ?），保证 HITL 暂停/恢复同 session 场景下总耗时正确。
 *
 * @author bigchui
 */
public class ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationStore.class);

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE agentx_conversation (
                id               BIGINT       NOT NULL  COMMENT '主键ID',
                conversation_id  VARCHAR(100) NOT NULL  COMMENT '会话窗口ID',
                session_id       VARCHAR(100) NOT NULL  COMMENT '本次调用ID',
                user_id          VARCHAR(100) DEFAULT NULL COMMENT '用户ID',
                question         LONGTEXT     NOT NULL  COMMENT '用户提问',
                status           VARCHAR(20)  DEFAULT 'running' COMMENT '执行状态: running/completed/interrupted/error',
                created_at       TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
                completed_at     TIMESTAMP(3) DEFAULT NULL COMMENT '完成时间',
                duration_ms      BIGINT       DEFAULT 0 COMMENT '端到端耗时（毫秒，恢复场景累加）',
                rounds           INT          DEFAULT 0 COMMENT 'ReAct轮次（LLM推理轮数）',
                tool_calls       INT          DEFAULT 0 COMMENT '工具执行总次数',
                tool_failures    INT          DEFAULT 0 COMMENT '失败工具次数',
                prompt_tokens    BIGINT       DEFAULT 0 COMMENT '输入token累计',
                completion_tokens BIGINT      DEFAULT 0 COMMENT '输出token累计',
                total_tokens     BIGINT       DEFAULT 0 COMMENT 'prompt+completion',
                model_name       VARCHAR(128) DEFAULT NULL COMMENT '实际使用的模型名',
                PRIMARY KEY (id)
            ) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AgentX会话窗口表'
            """;

    private static final String CREATE_UK_SESSION_SQL = """
            CREATE UNIQUE INDEX uk_conv_session ON agentx_conversation (session_id)
            """;

    private static final String CREATE_IDX_CONV_SQL = """
            CREATE INDEX idx_conv_id ON agentx_conversation (conversation_id)
            """;

    private static final String CREATE_IDX_CREATED_SQL = """
            CREATE INDEX idx_conv_created ON agentx_conversation (created_at)
            """;

    private static final String INSERT_SQL = """
            INSERT INTO agentx_conversation (id, conversation_id, session_id, user_id, question, status, created_at)
            VALUES (?, ?, ?, ?, ?, 'running', CURRENT_TIMESTAMP(3))
            """;

    private static final String UPDATE_TERMINAL_SQL = """
            UPDATE agentx_conversation
            SET status = ?,
                completed_at = CASE WHEN ? = 'completed' THEN CURRENT_TIMESTAMP(3) ELSE completed_at END,
                rounds = ?,
                tool_calls = ?,
                tool_failures = ?,
                prompt_tokens = ?,
                completion_tokens = ?,
                total_tokens = ?,
                duration_ms = COALESCE(duration_ms, 0) + ?,
                model_name = ?
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
                    // 迁移：删除已废弃的 trace_id 列（OTel traceId 不再落自建表）
                    try {
                        jdbcTemplate.execute("ALTER TABLE agentx_conversation DROP COLUMN trace_id");
                    } catch (Exception e) {
                        log.debug("trace_id column drop skipped (may not exist): {}", e.getMessage());
                    }
                    try {
                        jdbcTemplate.execute(CREATE_UK_SESSION_SQL);
                        jdbcTemplate.execute(CREATE_IDX_CONV_SQL);
                        jdbcTemplate.execute(CREATE_IDX_CREATED_SQL);
                    } catch (Exception e) {
                        log.debug("Index creation skipped: {}", e.getMessage());
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
     * 终态更新：连同运行观测汇总一并写入。
     *
     * @param sessionId         本次调用ID
     * @param status            终态：completed/interrupted/error
     * @param rounds            ReAct 轮次
     * @param toolCalls         工具执行总次数
     * @param toolFailures      失败工具次数
     * @param promptTokens      输入 token 累计
     * @param completionTokens  输出 token 累计
     * @param durationMs        本段执行耗时（同 session 恢复场景在已有值上累加）
     * @param modelName         实际使用的模型名（未知为 null）
     */
    public void updateTerminal(long sessionId, String status,
                               int rounds, int toolCalls, int toolFailures,
                               long promptTokens, long completionTokens,
                               long durationMs, String modelName) {
        ensureInitialized();
        jdbcTemplate.update(UPDATE_TERMINAL_SQL,
                status, status,
                rounds, toolCalls, toolFailures,
                promptTokens, completionTokens,
                promptTokens + completionTokens,
                durationMs,
                modelName,
                String.valueOf(sessionId));
        log.debug("Updated conversation terminal: sessionId={}, status={}, rounds={}, tools={}/{}, tokens={}/{}",
                sessionId, status, rounds, toolCalls, toolFailures, promptTokens, completionTokens);
    }
}
