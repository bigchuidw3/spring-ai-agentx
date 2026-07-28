package com.agentx.ai.samples.v1_1;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.model.AgentResult;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.model.ThinkingMode;
import com.agentx.ai.samples.TestConfig;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * 结构化输出 + 会话落库测试。
 */
public class StructuredOutputSessionTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static class ProjectStackSummary {
        @JsonProperty("language")
        private String language;

        @JsonProperty("framework")
        private String framework;

        @JsonProperty("database")
        private String database;

        @JsonProperty("cache")
        private String cache;

        @JsonProperty("deployment")
        private String deployment;

        @JsonProperty("summary")
        private String summary;

        public ProjectStackSummary() {
        }

        @Override
        public String toString() {
            return "ProjectStackSummary{language='%s', framework='%s', database='%s', cache='%s', deployment='%s', summary='%s'}"
                    .formatted(language, framework, database, cache, deployment, summary);
        }
    }

    public static void testStructuredOutputWithSessionPersistence() {
        TestConfig.printTestHeader("结构化输出 + 会话落库测试");

        ChatModel chatModel = TestConfig.createDeepSeekV4ChatModel();
        DataSource dataSource = TestConfig.createMySqlDataSource();
        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .dataSource(dataSource)
                .thinkingMode(ThinkingMode.REASONING_CONTENT)
                .maxRounds(6)
                .build();

        String convId = TestConfig.randomConvId();
        String userId = TestConfig.randomUserId("user_struct_session");
        System.out.println("convId = " + convId);

        RunnableParams plainParams = RunnableParams.builder()
                .conversationId(convId)
                .userId(userId)
                .build();

        RunnableParams structuredParams = RunnableParams.builder()
                .conversationId(convId)
                .userId(userId)
                .outputType(ProjectStackSummary.class)
                .build();

        String query1 = "请记住我的项目技术栈偏好：Java 21、Spring Boot、MySQL、Redis、Kubernetes。"
                + "先不要结构化输出，只用一句话确认你记住了。";
        System.out.println("Q1: " + query1);
        AgentResult result1 = agent.callForResult(query1, plainParams);
        if (result1 instanceof AgentResult.Completed c) {
            System.out.println("A1: " + c.answer());
        } else {
            System.out.println("第一轮未完成: " + result1);
            return;
        }

        String query2 = "基于我刚才给你的技术栈偏好，按 JSON 输出 language、framework、database、cache、deployment、summary。";
        System.out.println("\nQ2: " + query2);
        AgentResult result2 = agent.callForResult(query2, structuredParams);
        if (!(result2 instanceof AgentResult.Completed c2)) {
            System.out.println("第二轮未完成: " + result2);
            return;
        }

        System.out.println("A2 (raw JSON):\n" + c2.answer());
        try {
            ProjectStackSummary summary = objectMapper.readValue(c2.answer(), ProjectStackSummary.class);
            System.out.println("解析成功: " + summary);
        } catch (Exception e) {
            System.err.println("解析失败: " + e.getMessage());
        }

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        System.out.println("\n--- 落库验证 convId=" + convId + " ---");

        List<Map<String, Object>> conversationRows = jdbc.queryForList(
                "SELECT session_id, status, LEFT(question, 60) AS question, created_at, completed_at "
                        + "FROM agentx_conversation WHERE conversation_id = ? ORDER BY created_at",
                convId);
        System.out.println("[agentx_conversation] 共 " + conversationRows.size() + " 行");
        conversationRows.forEach(row -> System.out.println("  " + row));

        List<Map<String, Object>> sessionRows = jdbc.queryForList(
                "SELECT state_key, COUNT(*) AS row_count, COALESCE(SUM(CHAR_LENGTH(state_data)), 0) AS total_chars "
                        + "FROM agentx_session WHERE conversation_id = ? GROUP BY state_key ORDER BY state_key",
                convId);
        System.out.println("[agentx_session state_key 分布]");
        sessionRows.forEach(row -> System.out.println("  " + row.get("state_key")
                + " : rows=" + row.get("row_count")
                + ", chars=" + row.get("total_chars")));

        List<Map<String, Object>> previewRows = jdbc.queryForList(
                "SELECT state_key, item_index, LEFT(state_data, 160) AS data_preview, LENGTH(state_data) AS data_len "
                        + "FROM agentx_session WHERE conversation_id = ? ORDER BY state_key, item_index, id",
                convId);
        System.out.println("[agentx_session] 前 " + Math.min(previewRows.size(), 8) + " 条预览");
        previewRows.stream().limit(8).forEach(row -> System.out.println("  idx=" + row.get("item_index")
                + " key=" + row.get("state_key")
                + " len=" + row.get("data_len")
                + " data=" + row.get("data_preview")));
    }

    public static void main(String[] args) {
        System.out.println("===============================================");
        System.out.println(" Structured Output + Session Test (v1_1)");
        System.out.println("===============================================");
        System.out.println("ChatModel: " + TestConfig.CHAT_MODEL);
        System.out.println("===============================================");

        testStructuredOutputWithSessionPersistence();

        System.out.println("\n===============================================");
        System.out.println("       Test Completed");
        System.out.println("===============================================");
    }
}
