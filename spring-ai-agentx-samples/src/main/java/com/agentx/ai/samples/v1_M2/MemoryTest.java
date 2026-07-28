package com.agentx.ai.samples.v1_M2;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.memory.LongTermMemoryConfig;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.model.ThinkingMode;
import com.agentx.ai.samples.TestConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;

import javax.sql.DataSource;

/**
 * 当前会话记忆 + 长期记忆测试。
 *
 * <p>测试内容：
 * <ul>
 *   <li>测试 1：当前会话记忆（agentx_session）— 同一会话内可记住上下文，新会话回忆不到</li>
 *   <li>测试 2：长期记忆（agentx_long_term_memory）— 跨会话抽取与注入</li>
 * </ul>
 *
 * @author bigchui
 */
public class MemoryTest {

    /**
     * 测试 1：当前会话记忆（agentx_session）。
     */
    public static void testShortTermMemory() throws Exception {
        TestConfig.printTestHeader("测试 1：当前会话记忆（agentx_session）");

        DataSource dataSource = TestConfig.createMySqlDataSource();
        ChatModel chatModel = TestConfig.createDeepSeekV4ChatModel();
        String userId = TestConfig.randomUserId("user_st");
        String convId = TestConfig.randomConvId();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .dataSource(dataSource)
                .thinkingMode(ThinkingMode.REASONING_CONTENT)
                .maxRounds(5)
                .build();

        RunnableParams params = TestConfig.buildParams(convId, userId);

        String query1 = "我叫李四，我是java开发者，正在做一个agent项目";
        System.out.println("--- Round 1（同一会话）---");
        System.out.println("Q: " + query1);
        System.out.println("A: " + agent.call(query1, params));

        String query2 = "我刚才说我叫什么？在做什么项目？";
        System.out.println("\n--- Round 2（同一会话，短期记忆生效）---");
        System.out.println("Q: " + query2);
        System.out.println("A: " + agent.call(query2, params));

        // 新会话：短期记忆不跨会话
        RunnableParams params2 = TestConfig.buildParams(TestConfig.randomConvId(), userId);
        String query3 = "你知道我叫什么吗？";
        System.out.println("\n--- Round 3（新会话，短期记忆无法跨会话）---");
        System.out.println("Q: " + query3);
        System.out.println("A: " + agent.call(query3, params2));

        System.out.println("\n>>> 结论：短期记忆只在同一会话内有效。");
    }

    /**
     * 测试 2：长期记忆（agentx_long_term_memory）。
     *
     * <p>会话 1：让 Agent 记住用户的身份与项目信息。
     * 会话 2：新会话通过 userId 语义检索长期记忆，无需重新介绍。
     */
    public static void testLongTermMemory() throws Exception {
        TestConfig.printTestHeader("测试 2：长期记忆（agentx_long_term_memory）");

        DataSource mysqlDataSource = TestConfig.createMySqlDataSource();
        DataSource pgDataSource = TestConfig.createPgDataSource();
        ChatModel chatModel = TestConfig.createChatModel();
        EmbeddingModel embeddingModel = TestConfig.createEmbeddingModel();
        String userId = TestConfig.randomUserId("user_lt");

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .dataSource(mysqlDataSource)
                .longTermMemory(LongTermMemoryConfig.builder()
                        .vectorStore(TestConfig.createPgVectorStore(pgDataSource, embeddingModel))
                        .build())
                .maxRounds(5)
                .build();

        // 会话 1：注入值得记住的事实
        RunnableParams params1 = TestConfig.buildParams(TestConfig.randomConvId(), userId);
        System.out.println("--- 会话 1：注入事实 ---");
        TestConfig.streamAndPrint(agent,
                "我叫bigchui，是Java开发者，正在做一个基于 Spring AI 的 agent 框架。",
                params1);

        // 等待异步抽取与入库
        System.out.println("等待异步长期记忆入库...");
        Thread.sleep(15000);

        // 会话 2：新会话，验证长期记忆注入
        RunnableParams params2 = TestConfig.buildParams(TestConfig.randomConvId(), userId);
        System.out.println("--- 会话 2（新会话，长期记忆注入）---");
        TestConfig.streamAndPrint(agent,
                "bigchui的项目是什么？",
                params2);

        System.out.println(">>> 结论：长期记忆跨会话自动注入。");
        Thread.sleep(5000);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("===============================================");
        System.out.println("       会话记忆 + 长期记忆测试");
        System.out.println("===============================================");
        System.out.println("ChatModel:   " + TestConfig.CHAT_MODEL);
        System.out.println("Embedding:   " + TestConfig.EMBEDDING_MODEL);
        System.out.println("===============================================");

        int testNumber = 2;

        switch (testNumber) {
            case 1 -> testShortTermMemory();
            case 2 -> testLongTermMemory();
            default -> System.out.println("无效的测试编号: " + testNumber);
        }

        System.out.println("\n===============================================");
        System.out.println("       Test Completed");
        System.out.println("===============================================");
    }
}
