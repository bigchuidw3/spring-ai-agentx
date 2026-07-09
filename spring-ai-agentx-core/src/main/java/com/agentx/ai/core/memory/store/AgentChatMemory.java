package com.agentx.ai.core.memory.store;

import com.agentx.ai.core.stage.ThinkTagParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;


/**
 * 基于 JDBC 的会话记忆实现（MySQL 专用）。
 *
 * <p>自动创建 {@code agentx_session} 表，按 conversationId 存储问答对。
 * 与 {@link JdbcMemoryStore}（{@code agentx_memory} 表）共同构成 DataSource 模式的完整存储方案。
 *
 * <p>表结构（MySQL）：
 * <pre>
 * CREATE TABLE agentx_session (
 *     id              BIGINT       NOT NULL,
 *     conversation_id VARCHAR(100) NOT NULL,
 *     user_id         VARCHAR(100) DEFAULT NULL,
 *     question        LONGTEXT     NOT NULL,
 *     answer          LONGTEXT     DEFAULT NULL,
 *     think           LONGTEXT     DEFAULT NULL,
 *     timeline        LONGTEXT     DEFAULT NULL,
 *     created_at      TIMESTAMP    DEFAULT NULL,
 *     PRIMARY KEY (id)
 * ) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
 * </pre>
 *
 * @author bigchui
 *
 */
public class AgentChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(AgentChatMemory.class);

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE agentx_session (
                id              BIGINT       NOT NULL  COMMENT '主键ID',
                conversation_id VARCHAR(100) NOT NULL  COMMENT '会话ID',
                user_id         VARCHAR(100) DEFAULT NULL COMMENT '用户ID',
                question        LONGTEXT     NOT NULL  COMMENT '用户提问',
                answer          LONGTEXT     DEFAULT NULL COMMENT 'Agent回答（启动时为空，中断/完成后更新）',
                think           LONGTEXT     DEFAULT NULL COMMENT '模型思考内容',
                timeline        LONGTEXT     DEFAULT NULL COMMENT '时间线JSON（合并后的思考/正文/工具调用序列）',
                created_at      TIMESTAMP    DEFAULT NULL COMMENT '创建时间',
                PRIMARY KEY (id)
            ) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AgentX会话记录表'
            """;

    private static final String CREATE_INDEX_SQL = """
            CREATE INDEX idx_agentx_session_conv ON agentx_session (conversation_id)
            """;

    private static final String CREATE_INDEX_USER_SQL = """
            CREATE INDEX idx_agentx_session_user ON agentx_session (user_id)
            """;

    private static final String INSERT_SQL = """
            INSERT INTO agentx_session (id, conversation_id, user_id, question, answer, think, timeline, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """;

    /**
     * 插入或更新会话记录。
     * <p>
     * 启动时写入（answer 为空），中断/完成时更新 answer / think / timeline。
     * 同一 sessionId 的多次调用自动合并为一条记录。
     */
    private static final String UPSERT_SQL = """
            INSERT INTO agentx_session (id, conversation_id, user_id, question, answer, think, timeline, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON DUPLICATE KEY UPDATE
                answer   = VALUES(answer),
                think    = VALUES(think),
                timeline = VALUES(timeline)
            """;

    /**
     * 给已有表补加 timeline 列（表已存在时执行）。
     */
    private static final String ALTER_ADD_TIMELINE_SQL = """
            ALTER TABLE agentx_session ADD COLUMN timeline LONGTEXT DEFAULT NULL COMMENT '时间线JSON（合并后的思考/正文/工具调用序列）'
            """;

    private static final String SELECT_SQL = """
            SELECT question, answer FROM agentx_session
            WHERE conversation_id = ? AND answer IS NOT NULL AND answer != ''
            ORDER BY created_at ASC
            """;

    private static final String DELETE_SQL = """
            DELETE FROM agentx_session WHERE conversation_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private volatile boolean initialized = false;

    public AgentChatMemory(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * 初始化表结构（建表 + 建索引）。
     * 应在构建 Agent 时调用，而非首次对话时延迟初始化。
     */
    public void initialize() {
        ensureInitialized();
    }

    private void ensureInitialized() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    // MySQL 专用建表语句（utf8mb4 + 内联注释），表已存在时静默跳过
                    try {
                        jdbcTemplate.execute(CREATE_TABLE_SQL);
                    } catch (Exception e) {
                        log.debug("Table creation skipped (may already exist): {}", e.getMessage());
                        // 表已存在时补加 timeline 列（幂等：列已存在则跳过）
                        try {
                            jdbcTemplate.execute(ALTER_ADD_TIMELINE_SQL);
                            log.info("Added timeline column to existing agentx_session table");
                        } catch (Exception ex) {
                            log.debug("Timeline column migration skipped (may already exist): {}", ex.getMessage());
                        }
                    }
                    try {
                        jdbcTemplate.execute(CREATE_INDEX_SQL);
                        jdbcTemplate.execute(CREATE_INDEX_USER_SQL);
                    } catch (Exception e) {
                        log.debug("Index creation skipped: {}", e.getMessage());
                    }
                    initialized = true;
                    log.info("agentx_session table initialized");
                }
            }
        }
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        add(conversationId, null, messages);
    }

    /**
     * 带用户标识的会话存储。
     *
     * @param conversationId 会话 ID
     * @param userId         用户标识（可为 null）
     * @param messages       消息列表（需包含 UserMessage + AssistantMessage）
     */
    public void add(String conversationId, String userId, List<Message> messages) {
        ensureInitialized();

        String question = null;
        String answer = null;

        for (Message msg : messages) {
            if (msg instanceof UserMessage u && question == null) {
                question = u.getText();
            } else if (msg instanceof AssistantMessage a && answer == null) {
                answer = a.getText();
            }
        }

        if (question == null || answer == null) {
            log.warn("Skipping add: messages must contain at least one UserMessage and one AssistantMessage");
            return;
        }

        String cleanAnswer = ThinkTagParser.stripThinkTags(answer);
        jdbcTemplate.update(INSERT_SQL, IdWorker.getId(), conversationId, userId, question, cleanAnswer, null);
        log.debug("Added Q&A pair to agentx_session: conversationId={}, userId={}", conversationId, userId);
    }

    /**
     * 带用户标识和思考过程的会话存储（answer 和 think 已分离）。
     *
     * @param conversationId 会话 ID
     * @param userId         用户标识（可为 null）
     * @param question       用户问题
     * @param answer         助手回答（正文，不含 think）
     * @param think          思考过程（可为 null）
     */
    public void add(String conversationId, String userId, String question, String answer, String think) {
        add(conversationId, userId, question, answer, think, null);
    }

    /**
     * 带用户标识、思考过程和时间线的会话存储。
     *
     * @param conversationId 会话 ID
     * @param userId         用户标识（可为 null）
     * @param question       用户问题
     * @param answer         助手回答（正文，不含 think）
     * @param think          思考过程（可为 null）
     * @param timeline       时间线 JSON（TimelineSerializer 序列化结果，可为 null）
     */
    public void add(String conversationId, String userId, String question, String answer,
                    String think, String timeline) {
        add(conversationId, userId, question, answer, think, timeline, 0L);
    }

    /**
     * 带预生成 sessionId 的会话存储。
     * <p>
     * 允许调用方提前生成 sessionId 并复用，用于 trace、文件关联等场景与 agentx_session 主键对齐。
     * 传 0 时内部回退到 {@link IdWorker#getId()} 自动生成（保持与旧版本等价的行为）。
     *
     * @param conversationId 会话 ID
     * @param userId         用户标识（可为 null）
     * @param question       用户问题
     * @param answer         助手回答（正文，不含 think）
     * @param think          思考过程（可为 null）
     * @param timeline       时间线 JSON（可为 null）
     * @param sessionId      预生成的 session ID；为 0 时内部自动生成
     */
    public void add(String conversationId, String userId, String question, String answer,
                    String think, String timeline, long sessionId) {
        ensureInitialized();

        if (question == null || answer == null) {
            return;
        }

        long effectiveSessionId = sessionId != 0L ? sessionId : IdWorker.getId();
        jdbcTemplate.update(INSERT_SQL, effectiveSessionId, conversationId, userId, question, answer,
                think != null && !think.isEmpty() ? think : null,
                timeline != null && !timeline.isEmpty() ? timeline : null);
        log.debug("Added Q&A pair to agentx_session: conversationId={}, userId={}, sessionId={}",
                conversationId, userId, effectiveSessionId);
    }

    /**
     * 插入或更新会话记录（UPSERT）。
     * <p>
     * 启动时写入（answer 可为 null），中断/完成时更新 answer / think / timeline。
     * 是 {@link #add} 的超集——允许 answer 为空，且通过 {@code ON DUPLICATE KEY UPDATE} 合并同一 sessionId。
     *
     * @param sessionId      预生成的 session ID（必填，不能为 0）
     * @param conversationId 会话 ID
     * @param userId         用户标识（可为 null）
     * @param question       用户问题
     * @param answer         助手回答（可为 null，启动时为空，中断时为部分内容）
     * @param think          思考过程（可为 null）
     * @param timeline       时间线 JSON（可为 null）
     */
    public void upsert(long sessionId, String conversationId, String userId, String question,
                       String answer, String think, String timeline) {
        ensureInitialized();
        if (sessionId == 0L || question == null) {
            return;
        }
        jdbcTemplate.update(UPSERT_SQL, sessionId, conversationId, userId, question, answer,
                think != null && !think.isEmpty() ? think : null,
                timeline != null && !timeline.isEmpty() ? timeline : null);
        log.debug("Upserted agentx_session: sessionId={}, conversationId={}", sessionId, conversationId);
    }

    @Override
    public List<Message> get(String conversationId) {
        ensureInitialized();

        List<Message> result = new ArrayList<>();
        jdbcTemplate.query(SELECT_SQL, rs -> {
            result.add(new UserMessage(rs.getString("question")));
            result.add(new AssistantMessage(rs.getString("answer")));
        }, conversationId);

        return result;
    }

    /**
     * 清除指定会话的所有消息。
     */
    public void clear(String conversationId) {
        ensureInitialized();
        jdbcTemplate.update(DELETE_SQL, conversationId);
        log.debug("Cleared agentx_session: conversationId={}", conversationId);
    }
}
