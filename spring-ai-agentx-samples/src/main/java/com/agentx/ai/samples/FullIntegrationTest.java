package com.agentx.ai.samples;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.memory.store.SemanticMemoryStore;
import com.agentx.ai.core.tools.BashTool;
import com.agentx.ai.core.tools.FileSystemTools;
import com.agentx.ai.core.tools.GrepTool;
import com.agentx.ai.core.tools.SkillsTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;

import javax.sql.DataSource;

import static com.agentx.ai.core.utils.ToolMergeUtil.mergeTools;

/**
 * 完整集成测试 — 三层记忆架构 + Agent Skills。
 *
 * <p>验证完整场景：DataSource（会话+画像）+ PgVector（语义记忆）+ Skills（技能系统）+ 全量工具。
 * 所有记忆层和技能系统协同工作。
 *
 * @author bigchui
 * 
 */
public class FullIntegrationTest {

    /**
     * 完整集成测试：三层记忆 + Agent Skills。
     *
     * <p>场景：用户使用带记忆和技能的 Agent 完成一个跨会话的任务。
     * <ol>
     *   <li>会话 1：用户自我介绍（画像自动提取）+ 使用 skill 完成任务</li>
     *   <li>等待记忆持久化</li>
     *   <li>会话 2：新会话，验证画像注入 + 语义检索</li>
     * </ol>
     */
    public static void testFullIntegration() throws Exception {
        TestConfig.printTestHeader("完整集成测试：三层记忆 + Agent Skills");

        // ===== 初始化所有组件 =====
        ChatModel chatModel = TestConfig.createChatModel();
        EmbeddingModel embeddingModel = TestConfig.createEmbeddingModel();
        DataSource mysqlDataSource = TestConfig.createMySqlDataSource();
        DataSource pgDataSource = TestConfig.createPgDataSource();

        System.out.println("正在初始化 PgVectorStore...");
        PgVectorStore vectorStore = TestConfig.createPgVectorStore(pgDataSource, embeddingModel);
        System.out.println("PgVectorStore 初始化完成");

        ToolCallback[] allTools = mergeTools(
                BashTool.create(),
                FileSystemTools.create(),
                GrepTool.create(),
                new ToolCallback[]{SkillsTool.builder().addSkillsDirectory(TestConfig.SKILLS_DIR).build()}
        );

        String userId = TestConfig.randomUserId("user_full");

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .dataSource(mysqlDataSource)
                .semanticMemoryStore(new SemanticMemoryStore(vectorStore, embeddingModel, 30))
                .tools(allTools)
                .maxRounds(15)
                .build();

        // ===== 会话 1：自我介绍 + 使用 skill =====
        String convId1 = TestConfig.randomConvId();
        RunnableParams params1 = TestConfig.buildParams(convId1, userId);

        System.out.println("--- 会话 1：自我介绍 + 使用 skill ---");
        TestConfig.streamAndPrint(agent,
                "我叫bigchui，我是大模型智能体开发者，正在当前目录下做 Spring AI WZ项目。"
                        + "查看并分析当前目录的项目内容和主要功能，帮我在当前目录创建一个 readme.md，" +
                        "内容简要介绍这个 Spring AI WZ 框架的核心功能，简要描述。",
                params1);

        // 等待异步记忆提取 + 语义存储
        System.out.println("等待记忆持久化...");
        Thread.sleep(8000);

        // ===== 会话 2：新会话，验证记忆 + 继续任务 =====
        String convId2 = TestConfig.randomConvId();
        RunnableParams params2 = TestConfig.buildParams(convId2, userId);

        System.out.println("--- 会话 2（新会话，验证记忆注入）---");
        TestConfig.streamAndPrint(agent,
                "你知道我是谁吗？我在做什么项目？"
                        + "请用 ppt skill 帮我做一个关于 Spring AI WZ 框架的 5 页介绍 PPT，直接展示内容，不要封面和目录。",
                params2);

        System.out.println(">>> 完整集成测试完成：记忆（画像+语义）+ Skills + 工具 全部协同工作。");
    }

    // ===== Main =====

    public static void main(String[] args) throws Exception {
        System.out.println("===============================================");
        System.out.println("       Full Integration Test");
        System.out.println("===============================================");
        System.out.println("ChatModel:   " + TestConfig.CHAT_MODEL);
        System.out.println("Embedding:   " + TestConfig.EMBEDDING_MODEL);
        System.out.println("Skills:      " + TestConfig.SKILLS_DIR);
        System.out.println("===============================================");

        testFullIntegration();

        System.out.println("\n===============================================");
        System.out.println("       Test Completed");
        System.out.println("===============================================");
    }
}
