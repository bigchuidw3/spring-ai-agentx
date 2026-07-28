package com.agentx.ai.samples.v1_1;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.model.ThinkingMode;
import com.agentx.ai.samples.TestConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * SubAgent 流式 + 父会话落库测试。
 */
public class SubAgentSessionTest {

    public static void testStreamingSubAgentWithParentSessionPersistence() {
        TestConfig.printTestHeader("SubAgent 流式 + 父会话落库测试");

        ChatModel chatModel = TestConfig.createDeepSeekV4ChatModel();
        DataSource dataSource = TestConfig.createMySqlDataSource();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .dataSource(dataSource)
                .thinkingMode(ThinkingMode.REASONING_CONTENT)
                .instructions("你是一个协调员。所有翻译任务都必须调用 call_translator 工具委派给 translator 子 Agent 处理，自己不要直接翻译。")
                .subAgent(() -> ReactAgent.builder()
                        .name("translator")
                        .description("翻译专家，负责把中文翻译成日语")
                        .chatModel(chatModel)
                        .instructions("你是翻译专家。把用户提供的中文翻译成日语。只输出翻译结果，不要解释。")
                        .maxRounds(10)
                        .build())
                .maxRounds(10)
                .build();

        String convId = TestConfig.randomConvId();
        String userId = TestConfig.randomUserId("user_subagent_session");
        RunnableParams params = TestConfig.buildParams(convId, userId);
        String query = "请把这句话翻译成日语：人工智能正在改变软件开发的方式。务必调用 translator 子 Agent。";

        System.out.println("convId = " + convId);
        System.out.println("Q: " + query);

        agent.streamForResult(query, params)
                .doOnNext(TestConfig::printEvent)
                .doOnError(err -> System.err.println("Stream Error: " + err.getMessage()))
                .blockLast();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        System.out.println("\n--- 落库验证 convId=" + convId + " ---");

        List<Map<String, Object>> conversationRows = jdbc.queryForList(
                "SELECT session_id, status, LEFT(question, 80) AS question, created_at, completed_at "
                        + "FROM agentx_conversation WHERE conversation_id = ? ORDER BY created_at",
                convId);
        System.out.println("[agentx_conversation] 共 " + conversationRows.size() + " 行");
        conversationRows.forEach(row -> System.out.println("  " + row));

        List<Map<String, Object>> distinctSessionRows = jdbc.queryForList(
                "SELECT DISTINCT session_id FROM agentx_session WHERE conversation_id = ? ORDER BY session_id",
                convId);
        System.out.println("[agentx_session distinct session_id] 共 " + distinctSessionRows.size() + " 个（预期仅父 Agent 1 个）");
        distinctSessionRows.forEach(row -> System.out.println("  session_id=" + row.get("session_id")));

        List<Map<String, Object>> sessionRows = jdbc.queryForList(
                "SELECT session_id, state_key, COUNT(*) AS row_count, COALESCE(SUM(CHAR_LENGTH(state_data)), 0) AS total_chars "
                        + "FROM agentx_session WHERE conversation_id = ? "
                        + "GROUP BY session_id, state_key ORDER BY session_id, state_key",
                convId);
        System.out.println("[agentx_session state_key 分布]");
        sessionRows.forEach(row -> System.out.println("  session=" + row.get("session_id")
                + " key=" + row.get("state_key")
                + " rows=" + row.get("row_count")
                + " chars=" + row.get("total_chars")));

        List<Map<String, Object>> previewRows = jdbc.queryForList(
                "SELECT session_id, state_key, item_index, LEFT(state_data, 180) AS data_preview, LENGTH(state_data) AS data_len "
                        + "FROM agentx_session WHERE conversation_id = ? "
                        + "ORDER BY session_id, state_key, item_index, id",
                convId);
        System.out.println("[agentx_session] 前 " + Math.min(previewRows.size(), 8) + " 条预览");
        previewRows.stream().limit(8).forEach(row -> System.out.println("  session=" + row.get("session_id")
                + " idx=" + row.get("item_index")
                + " key=" + row.get("state_key")
                + " len=" + row.get("data_len")
                + " data=" + row.get("data_preview")));
    }

    public static void main(String[] args) {
        System.out.println("===============================================");
        System.out.println(" SubAgent Session Test (v1_1, stream)");
        System.out.println("===============================================");
        System.out.println("ChatModel: " + TestConfig.CHAT_MODEL);
        System.out.println("===============================================");

        testStreamingSubAgentWithParentSessionPersistence();

        System.out.println("\n===============================================");
        System.out.println("       Test Completed");
        System.out.println("===============================================");
    }
}
