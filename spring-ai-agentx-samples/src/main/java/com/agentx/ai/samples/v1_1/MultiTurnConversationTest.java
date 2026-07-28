package com.agentx.ai.samples.v1_1;

import com.agentx.ai.core.agent.ReactAgent;
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
 * 连续多轮对话测试（流式）。
 * <p>
 * 验证点：
 * 1. 同一 conversationId 跨多次 streamForResult 调用，Agent 能基于前文作答
 * 2. agentx_conversation 每轮新增一行（status=completed）
 * 3. agentx_session.original_messages 持续累积，包含完整 ToolCall/ToolResponse 链
 *
 * @author bigchui
 */
public class MultiTurnConversationTest {

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
     * 单次流式调用，实时打印事件。
     */
    private static void streamOnce(ReactAgent agent, String query, RunnableParams params) {
        System.out.println("Q: " + query);
        agent.streamForResult(query, params)
                .doOnNext(TestConfig::printEvent)
                .doOnError(err -> System.err.println("Stream Error: " + err.getMessage()))
                .blockLast();
        System.out.println();
    }

    /**
     * 验证 agentx_conversation 与 agentx_session 的累积情况。
     */
    private static void verifyAccumulated(DataSource dataSource, String convId, int expectedTurns) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        System.out.println("\n--- 落库验证 convId=" + convId + " ---");

        List<Map<String, Object>> convRows = jdbc.queryForList(
                "SELECT session_id, status, LEFT(question, 40) AS question, created_at, completed_at "
                        + "FROM agentx_conversation WHERE conversation_id = ? ORDER BY created_at",
                convId);
        System.out.println("[agentx_conversation] 共 " + convRows.size() + " 行 (预期 " + expectedTurns + ")");
        convRows.forEach(row -> System.out.println("  " + row));

        List<Map<String, Object>> msgRows = jdbc.queryForList(
                "SELECT state_key, item_index, LEFT(state_data, 120) AS data_preview, "
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
        System.out.println("  Multi-Turn Conversation Test (v1_1, stream)");
        System.out.println("===============================================");
        System.out.println("ChatModel: " + TestConfig.CHAT_MODEL);
        System.out.println("===============================================\n");

        ReactAgent agent = buildAgent();
        String convId = TestConfig.randomConvId();
        String userId = TestConfig.randomUserId("user_multi");
        RunnableParams params = TestConfig.buildParams(convId, userId);
        System.out.println("convId = " + convId + "\n");

        // Turn 1：引入北京天气（首次工具调用）
        streamOnce(agent, "帮我查一下北京现在的天气怎么样", params);

        // Turn 2：上下文接力（“呢” 指代上轮城市类别，模型应改查上海）
        streamOnce(agent, "上海呢？", params);

        // Turn 3：跨轮推理（不调工具，凭前两轮 tool 数据对比作答）
        streamOnce(agent, "北京和上海哪个气温更高？差多少度？", params);

        verifyAccumulated(TestConfig.createMySqlDataSource(), convId, 3);

        System.out.println("\n===============================================\n");
        System.out.println("       Test Completed");
        System.out.println("\n===============================================\n");
    }
}
