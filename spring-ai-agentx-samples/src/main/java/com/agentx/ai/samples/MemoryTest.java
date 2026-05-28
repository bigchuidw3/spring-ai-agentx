package com.agentx.ai.samples;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.memory.model.MemoryItem;
import com.agentx.ai.core.memory.store.MemoryStore;
import com.agentx.ai.core.memory.store.SemanticMemoryStore;
import com.agentx.ai.core.model.ThinkingMode;
import com.agentx.ai.core.tools.BashTool;
import com.agentx.ai.core.tools.FileSystemTools;
import com.agentx.ai.core.tools.GrepTool;
import com.agentx.ai.core.tools.SkillsTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;

import javax.sql.DataSource;
import java.util.List;

import static com.agentx.ai.core.utils.ToolMergeUtil.mergeTools;

/**
 * 三层记忆架构测试。
 *
 * <p>测试内容：
 * <ul>
 *   <li>测试 1：短期记忆（agentx_session）— call 非流式</li>
 *   <li>测试 2：短期 + 用户画像（agentx_session + agentx_memory）— stream</li>
 *   <li>测试 3：全部三层（agentx_session + agentx_memory + VectorStore RAG）— stream</li>
 * </ul>
 *
 * @author bigchui
 *
 */
public class MemoryTest {

    // ===== 测试方法 =====

    /**
     * 测试 1：短期记忆（agentx_session）。
     *
     * <p>禁用长期记忆，验证同一会话内可记住上下文，新会话则无法回忆。
     */
    public static void testShortTermMemory() throws Exception {
        TestConfig.printTestHeader("测试 1：短期记忆（agentx_session）");

        DataSource dataSource = TestConfig.createMySqlDataSource();
        ChatModel chatModel = TestConfig.createChatModel();
        String userId = TestConfig.randomUserId("user_st");
        String convId = TestConfig.randomConvId();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .dataSource(dataSource)
                .thinkingMode(ThinkingMode.REASONING_CONTENT)
                .profileMemoryEnabled(false)
                .maxRounds(5)
                .build();

        RunnableParams params = TestConfig.buildParams(convId, userId);

        // 第一轮：告诉 Agent 信息
        String query1 = "我叫李四，我是java开发者，正在做一个agent项目";
        System.out.println("--- Round 1（同一会话）---");
        System.out.println("Q: " + query1);
        String answer1 = agent.call(query1, params);
        System.out.println("A: " + answer1);

        // 第二轮：同一会话追问（短期记忆生效）
        String query2 = "我刚才说我叫什么？在做什么项目？";
        System.out.println("\n--- Round 2（同一会话，短期记忆生效）---");
        System.out.println("Q: " + query2);
        String answer2 = agent.call(query2, params);
        System.out.println("A: " + answer2);

        // 第三轮：新会话（短期记忆不跨会话）
        String convId2 = TestConfig.randomConvId();
        RunnableParams params2 = TestConfig.buildParams(convId2, userId);
        String query3 = "你知道我叫什么吗？";
        System.out.println("\n--- Round 3（新会话，短期记忆无法跨会话）---");
        System.out.println("Q: " + query3);
        String answer3 = agent.call(query3, params2);
        System.out.println("A: " + answer3);

        System.out.println("\n>>> 结论：短期记忆只在同一会话内有效，新会话无法回忆。");
    }

    /**
     * 测试 2：短期记忆 + 用户画像（agentx_session + agentx_memory）。
     *
     * <p>框架自动从对话中提取用户信息，跨会话持久化到 agentx_memory。
     * 新会话能自动加载用户画像。
     */
    public static void testProfileMemory() throws Exception {
        TestConfig.printTestHeader("测试 2：短期 + 用户画像（agentx_session + agentx_memory）");

        DataSource dataSource = TestConfig.createMySqlDataSource();
        ChatModel chatModel = TestConfig.createZhiPuChatModel();
        String userId = TestConfig.randomUserId("user_pf");

        // 注册工具：文件系统 + Skills + ask_user
        ToolCallback[] allTools = mergeTools(
                BashTool.create(),
                FileSystemTools.create(),
                GrepTool.create(),
                new ToolCallback[]{SkillsTool.builder().addSkillsDirectory(TestConfig.SKILLS_DIR).build()}
        );

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .dataSource(dataSource)
                .profileMemoryEnabled(false)
                .maxRounds(5)
                .tools(allTools)
                .thinkingMode(ThinkingMode.REASONING_CONTENT)
                .build();

        // 会话 1：告诉 Agent 用户信息
        String convId1 = TestConfig.randomConvId();
        RunnableParams params1 = TestConfig.buildParams(convId1, userId);

        System.out.println("--- 会话 1：告诉 Agent 用户信息（stream）---");
        TestConfig.collectStreamEvents(agent.streamForResult("帮我本地", params1));

//        // 等待异步记忆提取
//        System.out.println("等待异步记忆提取...");
//        Thread.sleep(20000);
//
//        // 打印 agentx_memory 中的画像
//        MemoryStore memoryStore = agent.getMemoryStore();
//        if (memoryStore != null) {
//            List<MemoryItem> memories = memoryStore.findByUserId(userId);
//            System.out.println("\n--- agentx_memory 中的画像 ---");
//            for (MemoryItem m : memories) {
//                System.out.println("  [" + m.getType() + "] " + m.getContent());
//            }
//            System.out.println("  共 " + memories.size() + " 条记忆");
//        }
//
//        // 会话 2：新会话，验证画像自动注入
//        String convId2 = TestConfig.randomConvId();
//        RunnableParams params2 = TestConfig.buildParams(convId2, userId);
//
//        System.out.println("\n--- 会话 2（新会话，用户画像自动注入）---");
//        TestConfig.collectStreamEvents(agent.streamForResult("你知道我叫什么吗？我擅长什么编程语言？我的代码风格是什么？", params2));
//
//        System.out.println(">>> 结论：用户画像跨会话持久化，新会话能自动回忆用户信息。");
    }

    /**
     * 测试 3：全部三层（agentx_session + agentx_memory + VectorStore RAG）。
     *
     * <p>模拟多轮对话积累 Q&A，验证：
     * - 每次对话 Q&A 异步存入 PgVector
     * - 对话时通过语义检索获取相关历史知识
     * - Q&A 达到阈值后自动合并为摘要
     */
    public static void testSemanticMemory() throws Exception {
        TestConfig.printTestHeader("测试 3：全部三层（agentx_session + agentx_memory + VectorStore RAG）");

        DataSource mysqlDataSource = TestConfig.createMySqlDataSource();
        DataSource pgDataSource = TestConfig.createPgDataSource();
        ChatModel chatModel = TestConfig.createChatModel();
        EmbeddingModel embeddingModel = TestConfig.createEmbeddingModel();

        // 初始化 PgVectorStore
        System.out.println("正在初始化 PgVectorStore...");
        PgVectorStore vectorStore = TestConfig.createPgVectorStore(pgDataSource, embeddingModel);
        System.out.println("PgVectorStore 初始化完成");

        String userId = TestConfig.randomUserId("user_sem");

        // 阈值设为 5 便于测试
        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .dataSource(mysqlDataSource)
                .semanticMemoryStore(new SemanticMemoryStore(vectorStore, embeddingModel, 5))
                .tools(FileSystemTools.create())
                .maxRounds(5)
                .build();

        // 模拟多轮对话，积累 Q&A 到 VectorStore
        String[] conversations = {
                "我们项目使用 Spring Boot 3.2 + MySQL 8.0，帮我介绍一下这个技术栈",
                "订单表有 500 万行数据，查询很慢，怎么优化？",
                "我们用 Redis 做缓存，key 的过期时间应该设置多久？",
                "怎么给订单表的 status 字段加索引？",
                "Spring Boot 中怎么配置多数据源？",
                "帮我写一个 Redis 工具类",
                "MySQL 的 EXPLAIN 怎么看执行计划？",
                "我们项目的日志用的是 Logback，怎么配置按天滚动？",
                "帮我设计一个用户表的 Schema",
                "MySQL 死锁是什么原因导致的？"
        };

        for (int i = 0; i < conversations.length; i++) {
            String convId = TestConfig.randomConvId();
            RunnableParams params = TestConfig.buildParams(convId, userId);

            System.out.println("--- 对话 " + (i + 1) + "/" + conversations.length + " ---");
            TestConfig.streamAndPrint(agent, conversations[i], params);

            // 短暂等待异步存储
            Thread.sleep(3000);
        }

        // 等待摘要合并完成
        System.out.println("\n等待摘要合并...");
        Thread.sleep(15000);

        // 新会话：测试语义检索
        String convId = TestConfig.randomConvId();
        RunnableParams params = TestConfig.buildParams(convId, userId);

        System.out.println("--- 新会话：测试语义检索 ---");
        TestConfig.streamAndPrint(agent,
                "我的项目数据库 update 没反应，有什么建议吗？",
                params);

        System.out.println(">>> 结论：语义记忆能检索到跨会话的历史知识，即使不在画像中。");
        Thread.sleep(60 * 1000);
    }

    // ===== Main =====

    public static void main(String[] args) throws Exception {
        System.out.println("===============================================");
        System.out.println("       三层记忆架构测试");
        System.out.println("===============================================");
        System.out.println("ChatModel:   " + TestConfig.CHAT_MODEL);
        System.out.println("Embedding:   " + TestConfig.EMBEDDING_MODEL);
        System.out.println("===============================================");

        int testNumber = 1;

        switch (testNumber) {
            case 1 -> testShortTermMemory();
            case 2 -> testProfileMemory();
            case 3 -> testSemanticMemory();
            default -> System.out.println("无效的测试编号: " + testNumber);
        }

        System.out.println("\n===============================================");
        System.out.println("       Test Completed");
        System.out.println("===============================================");
    }
}
