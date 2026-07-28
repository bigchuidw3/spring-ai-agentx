package com.agentx.ai.samples.v1_1;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.model.AgentResult;
import com.agentx.ai.core.model.AgentStreamEvent;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.model.ThinkingMode;
import com.agentx.ai.samples.TestConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * 工具调用 + 会话存储基础测试。
 *
 * 验证点：
 * 1. 流式 / 非流式接口都能正常调用工具
 * 2. agentx_conversation 表有开局与终态记录
 * 3. agentx_session 表 original_messages 含完整消息链（含 ToolCall / ToolResponse）
 *
 * @author bigchui
 */
public class ToolCallSessionTest {

    private static ReactAgent buildAgent() {
        ChatModel chatModel = TestConfig.createDeepSeekV4ChatModel();
        DataSource dataSource = TestConfig.createMySqlDataSource();
        ToolCallback[] tools = ToolCallbacks.from(new SessionTools());
        return ReactAgent.builder()
                .chatModel(chatModel)
                .dataSource(dataSource)
                .thinkingMode(ThinkingMode.REASONING_CONTENT)
                .tools(tools)
                .maxRounds(10)
                .build();
    }

    /**
     * 测试 1：非流式 callForResult + 工具调用 + 会话存储。
     */
    public static void testCallWithTool() {
        TestConfig.printTestHeader("测试 1：非流式 callForResult + 工具调用 + 会话存储");

        ReactAgent agent = buildAgent();
        String convId = TestConfig.randomConvId();
        String userId = TestConfig.randomUserId("user_call");
        RunnableParams params = TestConfig.buildParams(convId, userId);

        String query = "帮我查一下北京现在的天气怎么样，并用一句话给出穿衣建议";
        System.out.println("convId = " + convId);
        System.out.println("Q: " + query);

        AgentResult result = agent.callForResult(query, params);
        switch (result) {
            case AgentResult.Completed c -> {
                System.out.println("A: " + c.answer());
                if (c.think() != null && !c.think().isEmpty()) {
                    String preview = c.think();
                    System.out.println("(think) " + preview);
                }
            }
            case AgentResult.Failed f -> System.out.println("FAILED code=" + f.code() + " error=" + f.error());
            case AgentResult.Paused p -> System.out.println("PAUSED: " + p);
        }

        verifyPersistedMessages(TestConfig.createMySqlDataSource(), convId);
    }

    /**
     * 测试 2：流式 streamForResult + 工具调用 + 会话存储。
     */
    public static void testStreamWithTool() {
        TestConfig.printTestHeader("测试 2：流式 streamForResult + 工具调用 + 会话存储");

        ReactAgent agent = buildAgent();
        String convId = TestConfig.randomConvId();
        String userId = TestConfig.randomUserId("user_stream");
        RunnableParams params = TestConfig.buildParams(convId, userId);

        String query = "查一下上海的天气，然后告诉我适合出门吗";
        System.out.println("convId = " + convId);
        System.out.println("Q: " + query);

        agent.streamForResult(query, params)
                .doOnNext(TestConfig::printEvent)
                .doOnError(err -> System.err.println("Stream Error: " + err.getMessage()))
                .blockLast();

        verifyPersistedMessages(TestConfig.createMySqlDataSource(), convId);
    }

    /**
     * 查询 agentx_conversation 与 agentx_session，验证消息链落库情况。
     */
    private static void verifyPersistedMessages(DataSource dataSource, String convId) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        System.out.println("\n--- 落库验证 convId=" + convId + " ---");

        List<Map<String, Object>> convRows = jdbc.queryForList(
                "SELECT session_id, status, LEFT(question, 40) AS question, created_at, completed_at "
                        + "FROM agentx_conversation WHERE conversation_id = ? ORDER BY created_at",
                convId);
        System.out.println("[agentx_conversation] 共 " + convRows.size() + " 行");
        convRows.forEach(row -> System.out.println("  " + row));

        List<Map<String, Object>> msgRows = jdbc.queryForList(
                "SELECT state_key, item_index, LEFT(state_data, 140) AS data_preview, "
                        + "LENGTH(state_data) AS data_len "
                        + "FROM agentx_session WHERE conversation_id = ? "
                        + "ORDER BY state_key, item_index, id",
                convId);
        System.out.println("[agentx_session] 消息链共 " + msgRows.size() + " 条");
        msgRows.forEach(row -> System.out.println("  idx=" + row.get("item_index")
                + " key=" + row.get("state_key")
                + " len=" + row.get("data_len")
                + " data=" + row.get("data_preview")));
    }

    public static void main(String[] args) {
        System.out.println("===============================================");
        System.out.println("  Tool Call + Session Storage Test (v1_1)");
        System.out.println("===============================================");
        System.out.println("ChatModel: " + TestConfig.CHAT_MODEL);
        System.out.println("===============================================");

        int testNumber = 2;
        switch (testNumber) {
            case 1 -> testCallWithTool();
            case 2 -> testStreamWithTool();
            case 0 -> {
                testCallWithTool();
                testStreamWithTool();
            }
            default -> System.out.println("无效的测试编号: " + testNumber);
        }

        System.out.println("\n===============================================");
        System.out.println("       Test Completed");
        System.out.println("===============================================");
    }
}
