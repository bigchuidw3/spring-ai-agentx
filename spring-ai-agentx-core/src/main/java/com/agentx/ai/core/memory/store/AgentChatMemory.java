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
 * 基于 JDBC 的会话记忆实现。
 *
 * 自动创建 {@code agentx_session} 表，按 conversationId 存储问答对。
 * 与 {@link JdbcMemoryStore}（{@code agentx_memory} 表）共同构成 DataSource 模式的完整存储方案。
 *
 * 表结构：
 * CREATE TABLE IF NOT EXISTS agentx_session (
 *     id              BIGINT       NOT NULL,
 *     conversation_id VARCHAR(100) NOT NULL,
 *     user_id         VARCHAR(100) DEFAULT NULL,
 *     question        TEXT         NOT NULL,
 *     answer          TEXT         NOT NULL,
 *     think           TEXT         DEFAULT NULL,
 *     created_at      TIMESTAMP    DEFAULT NULL,
 *     PRIMARY KEY (id)
 * );
 *
 * @author bigchui
 * 
 */
public class AgentChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(AgentChatMemory.class);

    private static final String CHECK_TABLE_SQL = """
            SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'agentx_session'
            """;

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS agentx_session (
                id              BIGINT       NOT NULL,
                conversation_id VARCHAR(100) NOT NULL,
                user_id         VARCHAR(100) DEFAULT NULL,
                question        TEXT         NOT NULL,
                answer          TEXT         NOT NULL,
                think           TEXT         DEFAULT NULL,
                created_at      TIMESTAMP    DEFAULT NULL,
                PRIMARY KEY (id)
            )
            """;

    private static final String CREATE_INDEX_SQL = """
            CREATE INDEX idx_agentx_session_conv ON agentx_session (conversation_id)
            """;

    private static final String CREATE_INDEX_USER_SQL = """
            CREATE INDEX idx_agentx_session_user ON agentx_session (user_id)
            """;

    private static final String INSERT_SQL = """
            INSERT INTO agentx_session (id, conversation_id, user_id, question, answer, think, created_at)
            VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """;

    private static final String SELECT_SQL = """
            SELECT question, answer FROM agentx_session
            WHERE conversation_id = ? ORDER BY created_at ASC
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
                    Integer count = jdbcTemplate.queryForObject(CHECK_TABLE_SQL, Integer.class);
                    if (count == null || count == 0) {
                        jdbcTemplate.execute(CREATE_TABLE_SQL);
                        jdbcTemplate.execute(CREATE_INDEX_SQL);
                        jdbcTemplate.execute(CREATE_INDEX_USER_SQL);
                        log.info("agentx_session table created");
                    }
                    initialized = true;
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
        ensureInitialized();

        if (question == null || answer == null) {
            return;
        }

        jdbcTemplate.update(INSERT_SQL, IdWorker.getId(), conversationId, userId, question, answer,
                think != null && !think.isEmpty() ? think : null);
        log.debug("Added Q&A pair to agentx_session: conversationId={}, userId={}", conversationId, userId);
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
