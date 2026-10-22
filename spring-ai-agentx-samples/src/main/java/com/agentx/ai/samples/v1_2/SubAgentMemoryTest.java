package com.agentx.ai.samples.v1_2;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.model.ThinkingMode;
import com.agentx.ai.samples.TestConfig;
import org.springframework.ai.chat.model.ChatModel;

import javax.sql.DataSource;

/**
 * 子 Agent 多轮记忆测试（同名子 Agent 续接记忆）。
 *
 * <p>同一会话内两次调用同名子 Agent（研究员）：第二次调用加载上一次的 working_messages
 * 分区历史（含已产出结论、工具结果），续接记忆接着干，而不是从头重新调研。
 *
 * @author bigchui
 */
public class SubAgentMemoryTest {

    static ChatModel chatModel;
    static DataSource dataSource;

    public static void init() {
        chatModel = TestConfig.createDeepSeekV4ChatModel();
        dataSource = TestConfig.createMySqlDataSource();
    }

    public static void testMultiTurnMemory() {
        TestConfig.printTestHeader("子 Agent 多轮记忆（同名续接）");

        String convId = TestConfig.randomConvId();
        String userId = TestConfig.randomUserId("user_mem");

        ReactAgent pm = ReactAgent.builder()
                .chatModel(chatModel)
                .thinkingMode(ThinkingMode.REASONING_CONTENT)
                .dataSource(dataSource)
                .instructions("你是研发项目经理。遇到调研任务必须调用 call_researcher 委派给研究员，不要自己分析。")
                .subAgent(() -> ReactAgent.builder()
                        .name("researcher")
                        .description("研究员，负责深入调研并输出结论")
                        .chatModel(chatModel)
                        .thinkingMode(ThinkingMode.REASONING_CONTENT)
                        .instructions("你是研究员。围绕给定主题展开调研，输出结构化的调研结论。")
                        .maxRounds(30)
                        .build())
                .maxRounds(30)
                .build();

        RunnableParams params = TestConfig.buildParams(convId, userId);
        System.out.println("convId = " + convId);

        // 第一次：研究员调研技术现状，结论写进 (convId, researcher) 分区
        String q1 = "请研究员调研：企业级多 Agent 框架的技术现状。";
        System.out.println("\nQ1: " + q1);
        pm.streamForResult(q1, params)
                .doOnNext(TestConfig::printEvent)
                .blockLast();

        // 第二次：同名研究员续接上一次记忆（加载上次 working_messages，记得之前的结论）
        String q2 = "请研究员基于你刚才的调研，补充这些框架的横向对比。";
        System.out.println("\nQ2: " + q2);
        pm.streamForResult(q2, params)
                .doOnNext(TestConfig::printEvent)
                .blockLast();
    }

    public static void main(String[] args) {
        System.out.println("===============================================");
        System.out.println(" SubAgent Memory Test (v1_2)");
        System.out.println("===============================================");
        System.out.println("ChatModel: " + TestConfig.CHAT_MODEL);
        System.out.println("===============================================");

        init();
        testMultiTurnMemory();

        System.out.println("\n===============================================");
        System.out.println("       Test Completed");
        System.out.println("===============================================");
    }
}
