package com.agentx.ai.samples;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.model.AgentStreamEvent;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.model.ThinkingMode;
import com.agentx.ai.core.tools.BashTool;
import com.agentx.ai.core.tools.FileSystemTools;
import com.agentx.ai.core.tools.GrepTool;
import com.agentx.ai.core.tools.SkillsTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;

import javax.sql.DataSource;

import static com.agentx.ai.core.utils.ToolMergeUtil.mergeTools;

/**
 * Trace 审计测试 — 验证 agentx_trace 表记录和 Complete 事件 token 累计。
 *
 * @author bigchui
 */
public class TraceAuditTest {

    /**
     * 非流式多轮工具调用 + DataSource → agentx_trace 入库。
     */
    public static void testCall() {
        TestConfig.printTestHeader("非流式 Trace 测试");

        ChatModel chatModel = TestConfig.createDeepSeekV4ChatModel();
        DataSource dataSource = TestConfig.createMySqlDataSource();
        String userId = TestConfig.randomUserId("user_st");
        String convId = TestConfig.randomConvId();
        RunnableParams params = TestConfig.buildParams(convId, userId);

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(BashTool.create())
                .dataSource(dataSource)
                .thinkingMode(ThinkingMode.REASONING_CONTENT)
                .maxRounds(30)
                .build();

        String query = "扫描本地桌面有哪些文件，并输出结果到本地桌面上";
        System.out.println("Q: " + query);

        String result = agent.call(query,params);
        System.out.println("A: " + result);

    }

    /**
     * 流式多轮工具调用 + DataSource → agentx_trace 入库 + Complete 事件 token 累计。
     */
    public static void testStream() {
        TestConfig.printTestHeader("流式 Trace 测试");

        ChatModel chatModel = TestConfig.createDeepSeekV4ChatModel();
        DataSource dataSource = TestConfig.createMySqlDataSource();
        String userId = TestConfig.randomUserId("user_st");
        String convId = TestConfig.randomConvId();
        RunnableParams params = TestConfig.buildParams(convId, userId);

        ToolCallback[] allTools = mergeTools(
                BashTool.create(),
                FileSystemTools.create(),
                GrepTool.create(),
                new ToolCallback[]{SkillsTool.builder().addSkillsDirectory(TestConfig.SKILLS_DIR).build()}
        );

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(allTools)
                .thinkingMode(ThinkingMode.REASONING_CONTENT)
                .dataSource(dataSource)
                .maxRounds(30)
                .build();

        String query = "扫描本地桌面有哪些文件，并输出结果到本地桌面上";
        System.out.println("Q: " + query);

        TestConfig.collectStreamEvents(
                agent.streamForResult(query, params));

    }

    // ===== Main =====

    public static void main(String[] args) {
        System.out.println("===============================================");
        System.out.println("       Trace Audit Test");
        System.out.println("===============================================");
        System.out.println("ChatModel: " + TestConfig.CHAT_MODEL);
        System.out.println("===============================================");

        int testNumber = 1;

        switch (testNumber) {
            case 1 -> testCall();
            case 2 -> testStream();
            default -> System.out.println("无效的测试编号: " + testNumber);
        }

        System.out.println("\n===============================================");
        System.out.println("       Test Completed");
        System.out.println("===============================================");
    }
}
