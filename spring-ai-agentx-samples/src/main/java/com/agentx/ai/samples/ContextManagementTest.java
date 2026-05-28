package com.agentx.ai.samples;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.context.ContextPolicy;
import com.agentx.ai.core.model.AgentStreamEvent;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.tools.BashTool;
import com.agentx.ai.core.tools.FileSystemTools;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 上下文管理（Context Compaction）示例测试。
 * <p>
 * 修改 testNumber 切换测试场景，直接运行 main 方法即可。
 *
 * <pre>
 * 场景 1: 默认配置 — 使用默认上下文压缩策略
 * 场景 2: 自定义配置 — 低阈值 + 自定义保护工具
 * 场景 3: 不启用压缩 — 验证向后兼容
 * </pre>
 *
 * @author bigchui
 *
 */
public class ContextManagementTest {

    static int testNumber = 2;

    public static void main(String[] args) {
        ChatModel chatModel = TestConfig.createMiniMaxChatModel();

        switch (testNumber) {
            case 1 -> testDefaultPolicy(chatModel);
            case 2 -> testCustomPolicyWithProtectedTools(chatModel);
            case 3 -> testNoPolicy(chatModel);
            default -> System.out.println("Unknown test number: " + testNumber);
        }
    }

    /**
     * 场景 1: 默认上下文压缩配置。
     * <p>
     * 使用 ContextPolicy.defaults()（阈值 60000, 保留 4 个工具结果），
     * 配合多轮工具调用任务，观察压缩效果。
     */
    static void testDefaultPolicy(ChatModel chatModel) {
        TestConfig.printTestHeader("Test 1: 默认上下文压缩配置");

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(FileSystemTools.create())
                .tools(BashTool.create())
                .contextPolicy(ContextPolicy.defaults())
                .maxRounds(50)
                .build();

        RunnableParams params = RunnableParams.builder()
                .conversationId(TestConfig.randomConvId())
                .build();

        String query = "请依次执行以下操作：1) 列出当前目录下的文件 2) 读取 pom.xml 的前20行 "
                + "3) 用 bash 执行 echo hello 4) 再列出当前目录文件。每一步都要调用工具。";
        streamAndPrint(agent, query, params);
    }

    /**
     * 场景 2: 自定义配置 + 保护工具。
     * <p>
     * 设置低 token 阈值（更容易触发 auto_compact）。
     * SkillsTool 已内置保护，无需手动配置。
     */
    static void testCustomPolicyWithProtectedTools(ChatModel chatModel) {
        TestConfig.printTestHeader("Test 2: 自定义配置 + 保护工具");

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(FileSystemTools.create())
                .tools(BashTool.create())
                .contextPolicy(ContextPolicy.builder()
                        .tokenThreshold(5000)             // 低阈值，更容易触发 auto_compact
                        .keepRecentTools(2)               // 只保留最近 2 个工具结果
                        .maxToolLength(1000)              // 内容超过 1000 字符压缩
                        .build())
                .maxRounds(50)
                .thinkTagEnabled(true)
                .build();

        RunnableParams params = RunnableParams.builder()
                .conversationId(TestConfig.randomConvId())
                .build();

        String query = "请依次执行以下操作：1) 列出当前目录下的文件 2) 新建一个test.py，生成任意的测试python脚本，输出打印即可，必须大于50行代码 "
                + "3) 用 bash 执行 echo hello 4) 再列出当前目录文件。每一步都要调用工具。5) 在检查下当前环境python可不可用。6）在桌面新建一个te.txt，内容是hello te";
        streamAndPrint(agent, query, params);
    }

    /**
     * 场景 3: 不启用上下文压缩。
     * <p>
     * 不配置 contextPolicy，验证 Agent 行为与之前完全一致（向后兼容）。
     */
    static void testNoPolicy(ChatModel chatModel) {
        TestConfig.printTestHeader("Test 3: 不启用上下文压缩（向后兼容）");

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(FileSystemTools.create())
                .tools(BashTool.create())
                // 不配置 contextPolicy
                .maxRounds(50)
                .build();

        RunnableParams params = RunnableParams.builder()
                .conversationId(TestConfig.randomConvId())
                .build();

        String query = "列出当前目录的文件，然后用 bash 查看系统时间";
        streamAndPrint(agent, query, params);
    }

    // ==================== streamForResult 打印工具方法 ====================

    /**
     * 使用 streamForResult 调用 Agent 并打印完整的执行过程（Thinking、Text、ToolStart/End 等）。
     */
    static void streamAndPrint(ReactAgent agent, String query, RunnableParams params) {
        System.out.println("Q: " + query);
        System.out.println("---");

        agent.streamForResult(query, params)
                .doOnNext(TestConfig::printEvent)
                .doOnError(err -> System.err.println("\nError: " + err.getMessage()))
                .blockLast();

        System.out.println("\n");
    }
}
