package com.agentx.ai.samples.v1_1;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.context.ContextPolicy;
import com.agentx.ai.core.memory.store.DataSourceStorageFactory;
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
 * 6 层上下文压缩策略触发测试（流式）。
 * <p>
 * 修改 testNumber 切换测试场景，直接运行 main 方法即可：
 * <pre>
 * 1: L1 HistoricalToolList — 历史 ≥4 连续工具消息被字符串模板替换
 * 2: L2 LargeMsgOffloadWithKeep — 历史区域单条大消息 offload（保护 lastKeep）
 * 3: L3 LargeMsgOffloadNoKeep — 大消息 offload（仅保护最新 Assistant）
 * 4: L4 HistoricalRoundSummary — 历史轮次 LLM 摘要
 * 5: L5 CurrentRoundLargeMsg — 当前轮单条大消息 LLM 摘要
 * 6: L6 CurrentRoundOverall — 当前轮整体 LLM 压缩
 * 7: 综合 — 连续多轮，观察各层在真实对话中的触发
 * </pre>
 * <p>
 * 每个场景运行后查 agentx_session 表的 working_messages / offload_context 验证。
 *
 * @author bigchui
 */
public class CompressionLayerTest {

    static int testNumber = 1;

    public static void main(String[] args) {
        TestConfig.printTestHeader("Compression Layer Test #" + testNumber);
        ChatModel chatModel = TestConfig.createDeepSeekV4ChatModel();
        DataSource dataSource = TestConfig.createMySqlDataSource();

        switch (testNumber) {
            case 1 -> testL1(chatModel, dataSource);
            case 2 -> testL2(chatModel, dataSource);
            case 3 -> testL3(chatModel, dataSource);
            case 4 -> testL4(chatModel, dataSource);
            case 5 -> testL5(chatModel, dataSource);
            case 6 -> testL6(chatModel, dataSource);
            case 7 -> testAll(chatModel, dataSource);
            default -> System.out.println("Unknown test number: " + testNumber);
        }
    }

    // ==================== L1: HistoricalToolList ====================

    /**
     * 用单轮连续工具链稳定触发 L1。
     * 第一轮先累计完整工具链，第二轮进入前再用 msgThreshold 打开总闸门，
     * 避免首轮执行过程中被 L6 抢先压当前区。
     */
    static void testL1(ChatModel chatModel, DataSource dataSource) {
        ContextPolicy policy = ContextPolicy.builder()
                .lastKeep(2)
                .msgThreshold(8)
                .tokenThreshold(99999)
                .minConsecutiveToolMessages(4)
                .minCompressionTokens(20)
                .largePayloadTokens(99999)
                .currentRoundRatio(0.9)
                .build();
        ReactAgent agent = buildAgent(chatModel, dataSource, policy);
        RunnableParams params = RunnableParams.builder()
                .conversationId("test123456-1")
                .userId("default-111")
                .build();

        streamOnce(agent,
                "请严格按顺序完成北京天气查询，不要凭常识直接回答。"
                        + "先调用 getCityCode 获取北京编码；"
                        + "再基于这个编码调用 getCurrentSky、getCurrentHumidity、getCurrentWind；"
                        + "等所有工具都调用完成后，再给我一句简短结论。",
                params);
        streamOnce(agent, "把刚才那轮的工具调用记录整理一下，不要重新查询。", params);

        verifyState(dataSource, params.getConversationId(), "L1");
    }

    // ==================== L2: LargeMsgOffloadWithKeep ====================

    /**
     * 用 msgThreshold 延后首次压缩检查，等大消息移出 lastKeep 保护区后再进入策略链。
     * 否则大消息在第二轮开始前仍位于 lastKeep 内，更容易先被 L3 命中而不是 L2。
     */
    static void testL2(ChatModel chatModel, DataSource dataSource) {
        ContextPolicy policy = ContextPolicy.builder()
                .lastKeep(4)
                .msgThreshold(15)
                .tokenThreshold(99999)
                .minConsecutiveToolMessages(99)
                .minCompressionTokens(99999)
                .largePayloadTokens(300)
                .build();
        ReactAgent agent = buildAgent(chatModel, dataSource, policy);
        RunnableParams params = newParams();

        streamOnce(agent, "请调用 readLargeDocument 读取 project_plan，不要总结内容。", params);
        streamOnce(agent, "请调用 getWeather 查询北京天气，并用一句话返回结果。", params);
        streamOnce(agent, "请调用 getWeather 查询上海天气，并用一句话返回结果。", params);
        streamOnce(agent, "请调用 getWeather 查询广州天气，并用一句话返回结果。", params);
        streamOnce(agent, "现在总结一下之前所有的内容", params);

        verifyState(dataSource, params.getConversationId(), "L2");
    }

    // ==================== L3: LargeMsgOffloadNoKeep ====================

    /**
     * 用 msgThreshold 把首次压缩检查延后到最后一轮开始前，
     * 并把大消息放进 [size-lastKeep, latestUserMsgIndex) 区间，让 L2 扫不到而 L3 能扫到。
     */
    static void testL3(ChatModel chatModel, DataSource dataSource) {
        ContextPolicy policy = ContextPolicy.builder()
                .lastKeep(8)
                .msgThreshold(15)
                .tokenThreshold(99999)
                .minConsecutiveToolMessages(99)
                .minCompressionTokens(99999)
                .largePayloadTokens(300)
                .build();
        ReactAgent agent = buildAgent(chatModel, dataSource, policy);
        RunnableParams params = newParams();

        streamOnce(agent, "请调用 getWeather 查询北京天气，并用一句话返回结果。", params);
        streamOnce(agent, "请调用 getWeather 查询上海天气，并用一句话返回结果。", params);
        streamOnce(agent, "请调用 readLargeDocument 读取 tech_spec，不要总结内容。", params);
        streamOnce(agent, "请调用 getWeather 查询深圳天气，并用一句话返回结果。", params);
        streamOnce(agent, "现在总结之前所有内容", params);

        verifyState(dataSource, params.getConversationId(), "L3");
    }

    // ==================== L4: HistoricalRoundSummary ====================

    /**
     * 用 msgThreshold 把首次压缩检查放到第 3 轮开始前，确保前两轮完整落入历史区。
     * lastKeep 设为 1，让两轮 user→assistant 都能进入 L4 扫描范围。
     */
    static void testL4(ChatModel chatModel, DataSource dataSource) {
        ContextPolicy policy = ContextPolicy.builder()
                .lastKeep(1)
                .msgThreshold(9)
                .tokenThreshold(99999)
                .minConsecutiveToolMessages(99)
                .minCompressionTokens(100)
                .largePayloadTokens(99999)
                .maxLlmCompressionCount(2)
                .build();
        ReactAgent agent = buildAgent(chatModel, dataSource, policy);
        RunnableParams params = newParams();

        streamOnce(agent, "请调用 getCityDetail 介绍北京的景点、美食和文化，并用两三句话总结。", params);
        streamOnce(agent, "请调用 getCityDetail 介绍上海的景点、美食和文化，并用两三句话总结。", params);
        streamOnce(agent, "总结前两轮城市介绍的共同点和差异", params);

        verifyState(dataSource, params.getConversationId(), "L4");
    }

    // ==================== L5: CurrentRoundLargeMsg ====================

    /**
     * 单轮内先返回一条超大工具结果，再在同轮继续生成最终答案。
     * 压缩点发生在“工具返回后、最终回答前”的下一次 LLM 调用前，稳定命中 L5。
     */
    static void testL5(ChatModel chatModel, DataSource dataSource) {
        ContextPolicy policy = ContextPolicy.builder()
                .lastKeep(99)
                .msgThreshold(99999)
                .tokenThreshold(500)
                .minConsecutiveToolMessages(99)
                .minCompressionTokens(99999)
                .largePayloadTokens(300)
                .build();
        ReactAgent agent = buildAgent(chatModel, dataSource, policy);
        RunnableParams params = newParams();

        streamOnce(agent,
                "请先调用 readLargeDocument 读取 meeting_notes，不能凭常识直接回答。"
                        + "读取完成后，只基于文档内容提炼 3 条核心要点。",
                params);

        verifyState(dataSource, params.getConversationId(), "L5");
    }

    // ==================== L6: CurrentRoundOverall ====================

    /**
     * 用消息数总闸门稳定打开策略链，再让当前轮整体压缩落到 L6。
     * 单条消息都不大，且 lastKeep 极大、L1-L5 都不会先吃掉当前轮内容。
     */
    static void testL6(ChatModel chatModel, DataSource dataSource) {
        ContextPolicy policy = ContextPolicy.builder()
                .lastKeep(99)
                .msgThreshold(12)
                .tokenThreshold(99999)
                .minConsecutiveToolMessages(99)
                .minCompressionTokens(150)
                .largePayloadTokens(99999)
                .currentRoundRatio(0.3)
                .build();
        ReactAgent agent = buildAgent(chatModel, dataSource, policy);
        RunnableParams params = newParams();

        streamOnce(agent,
                "请严格按顺序完成，不要跳步，也不要合并工具调用。"
                        + "先为北京执行完整天气流程：getCityCode、getCurrentSky、getCurrentHumidity、getCurrentWind；"
                        + "再按同样流程处理上海；"
                        + "再按同样流程处理广州；"
                        + "再按同样流程处理深圳；"
                        + "所有工具结果都拿到后，再给我一段详细对比分析。",
                params);

        verifyState(dataSource, params.getConversationId(), "L6");
    }

    // ==================== 综合：连续多轮观察各层 ====================

    /**
     * 用一套相对中庸的配置，跑多轮真实对话，观察日志里哪层触发。
     */
    static void testAll(ChatModel chatModel, DataSource dataSource) {
        ContextPolicy policy = ContextPolicy.builder()
                .lastKeep(8)
                .msgThreshold(99999)
                .tokenThreshold(800)
                .minConsecutiveToolMessages(4)
                .minCompressionTokens(200)
                .largePayloadTokens(500)
                .currentRoundRatio(0.3)
                .build();
        ReactAgent agent = buildAgent(chatModel, dataSource, policy);
        RunnableParams params = newParams();

        streamOnce(agent, "查北京天气", params);
        streamOnce(agent, "查上海天气", params);
        streamOnce(agent, "读取文档 project_plan", params);
        streamOnce(agent, "查广州天气", params);
        streamOnce(agent, "查深圳天气", params);
        streamOnce(agent, "总结一下之前所有的内容", params);

        verifyState(dataSource, params.getConversationId(), "ALL");
    }

    // ==================== 公共方法 ====================

    private static ReactAgent buildAgent(ChatModel chatModel, DataSource dataSource, ContextPolicy policy) {
        ToolCallback[] tools = ToolCallbacks.from(new CompressionTestTools());
        return ReactAgent.builder()
                .chatModel(chatModel)
                .dataSource(dataSource)
                .thinkingMode(ThinkingMode.REASONING_CONTENT)
                .tools(tools)
                .contextPolicy(policy)
                .maxRounds(15)
                .build();
    }

    private static RunnableParams newParams() {
        return TestConfig.buildParams(TestConfig.randomConvId(), TestConfig.randomUserId("user_compress"));
    }

    private static void streamOnce(ReactAgent agent, String query, RunnableParams params) {
        System.out.println("\n>>> Q: " + query);
        agent.streamForResult(query, params)
                .doOnNext(TestConfig::printEvent)
                .doOnError(err -> System.err.println("Stream Error: " + err.getMessage()))
                .blockLast();
    }

    /**
     * 验证 working_messages 与 offload_context 是否有写入。
     */
    private static void verifyState(DataSource dataSource, String convId, String label) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        System.out.println("\n--- [" + label + "] 落库验证 convId=" + convId + " ---");

        List<Map<String, Object>> stateRows = jdbc.queryForList(
                "SELECT state_key, COUNT(*) AS cnt, SUM(LENGTH(state_data)) AS total_bytes "
                        + "FROM agentx_session WHERE conversation_id = ? GROUP BY state_key",
                convId);
        System.out.println("[agentx_session state_key 分布]");
        stateRows.forEach(row -> System.out.println("  " + row.get("state_key")
                + " : rows=" + row.get("cnt") + ", bytes=" + row.get("total_bytes")));

        List<Map<String, Object>> offloadRows = jdbc.queryForList(
                "SELECT item_index, LEFT(state_data, 100) AS preview "
                        + "FROM agentx_session WHERE conversation_id = ? AND state_key = 'offload_context' "
                        + "ORDER BY item_index LIMIT 5",
                convId);
        if (offloadRows.isEmpty()) {
            System.out.println("[offload_context] 无（未触发 L2/L3/L5，或未启用 session）");
        } else {
            System.out.println("[offload_context] 共 " + offloadRows.size() + "+ 条原文（前 5 条预览）：");
            offloadRows.forEach(row -> System.out.println("  idx=" + row.get("item_index")
                    + " data=" + row.get("preview")));
        }
    }
}
