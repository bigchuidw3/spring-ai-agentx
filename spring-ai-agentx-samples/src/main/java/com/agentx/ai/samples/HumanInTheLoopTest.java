package com.agentx.ai.samples;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.advisors.PauseAdvisor;
import com.agentx.ai.core.model.AgentResult;
import com.agentx.ai.core.model.PendingToolCall;
import com.agentx.ai.core.model.PauseState;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.tools.AskUserTool;
import com.agentx.ai.core.tools.FileSystemTools;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * Human-in-the-Loop 测试。
 *
 * <p>测试内容：
 * <ul>
 *   <li>测试 1：call 路径 — callForResult → Paused → resume</li>
 *   <li>测试 2：stream 路径 — streamForResult → Paused → resumeStream</li>
 *   <li>测试 3：call 路径 + 交互式输入</li>
 *   <li>测试 4：askUser(true) 快捷构建</li>
 *   <li>测试 5：流式 + 文件工具 + 多工具拦截 + 交互式确认</li>
 *   <li>测试 6：自定义 askUserTool + 流式 + 文件审批</li>
 * </ul>
 *
 * @author bigchui
 * 
 */
public class HumanInTheLoopTest {

    // ===== 测试方法 =====

    /**
     * 测试 1：call 路径的 Human-in-the-Loop。
     *
     * <p>流程：callForResult → Paused → 回答 → resume → 循环直到 Completed
     */
    public static void testCallPauseResume() {
        TestConfig.printTestHeader("测试 1：call 路径 — Pause/Resume");

        ChatModel chatModel = TestConfig.createChatModel();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(AskUserTool.create())
                .advisors(new PauseAdvisor("ask_user"))
                .maxRounds(10)
                .build();

        String query = "我想去旅行，帮我推荐一个目的地";
        System.out.println("Q: " + query);

        // 首次调用
        AgentResult result = agent.callForResult(query, RunnableParams.empty());

        // 循环处理暂停，直到 Agent 完成回答
        int pauseRound = 0;
        while (result instanceof AgentResult.Paused p) {
            pauseRound++;
            System.out.println("\n--- Agent 暂停（第 " + pauseRound + " 次提问）---");
            printPendingToolCalls(p.state());

            // 模拟用户回答
            Map<String, String> answers = new LinkedHashMap<>();
            for (PendingToolCall ptc : p.state().getPendingToolCalls()) {
                String mockAnswer = mockUserAnswer(pauseRound);
                System.out.println("用户回答: " + mockAnswer);
                answers.put(ptc.id(), mockAnswer);
            }

            System.out.println("\n--- 恢复 Agent 执行 ---");
            result = agent.resume(p.state(), answers);
        }

        if (result instanceof AgentResult.Completed c) {
            System.out.println("A: " + c.answer());
            System.out.println("\n（共暂停 " + pauseRound + " 次）");
        }
    }

    /**
     * 测试 2：stream 路径的 Human-in-the-Loop。
     *
     * <p>流程：streamForResult → Paused → 回答 → resumeStream → 循环直到 Completed
     */
    public static void testStreamPauseResume() {
        TestConfig.printTestHeader("测试 2：stream 路径 — Pause/Resume");

        ChatModel chatModel = TestConfig.createChatModel();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(AskUserTool.create())
                .advisors(new PauseAdvisor("ask_user"))
                .maxRounds(10)
                .build();

        String query = "帮我设计一个健身计划";
        System.out.println("Q: " + query);
        System.out.print("A: ");

        // 首次流式调用，收集事件
        PauseState pauseState = TestConfig.collectStreamEvents(agent.streamForResult(query, RunnableParams.empty()));
        System.out.println();

        // 循环处理暂停
        int pauseRound = 0;
        while (pauseState != null) {
            pauseRound++;
            System.out.println("\n--- Agent 暂停（第 " + pauseRound + " 次提问）---");
            printPendingToolCalls(pauseState);

            Map<String, String> answers = new LinkedHashMap<>();
            for (PendingToolCall ptc : pauseState.getPendingToolCalls()) {
                String mockAnswer = mockUserAnswer(pauseRound);
                System.out.println("用户回答: " + mockAnswer);
                answers.put(ptc.id(), mockAnswer);
            }

            System.out.println("\n--- 恢复 Agent 执行 ---");
            System.out.print("A: ");
            pauseState = TestConfig.collectStreamEvents(agent.resumeStream(pauseState, answers));
            System.out.println();
        }

        System.out.println("（共暂停 " + pauseRound + " 次）");
    }

    /**
     * 测试 3：call 路径 + 用户手动输入。
     *
     * <p>与测试 1 相同流程，但用户通过控制台实时输入回答。
     */
    public static void testCallInteractive() {
        TestConfig.printTestHeader("测试 3：call 路径 — 交互式");

        ChatModel chatModel = TestConfig.createChatModel();
        Scanner scanner = new Scanner(System.in);

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(AskUserTool.create())
                .advisors(new PauseAdvisor("ask_user"))
                .maxRounds(10)
                .build();

        System.out.print("请输入你的问题: ");
        String query = scanner.nextLine();
        System.out.println();

        AgentResult result = agent.callForResult(query, RunnableParams.empty());

        int pauseRound = 0;
        while (result instanceof AgentResult.Paused p) {
            pauseRound++;
            System.out.println("\n--- Agent 提问（第 " + pauseRound + " 次）---");
            printPendingToolCalls(p.state());

            Map<String, String> answers = new LinkedHashMap<>();
            for (PendingToolCall ptc : p.state().getPendingToolCalls()) {
                System.out.print("你的回答: ");
                String answer = scanner.nextLine();
                answers.put(ptc.id(), answer);
            }

            System.out.println("\n--- 恢复 Agent 执行 ---");
            result = agent.resume(p.state(), answers);
        }

        if (result instanceof AgentResult.Completed c) {
            System.out.println("\nA: " + c.answer());
            System.out.println("\n（共暂停 " + pauseRound + " 次）");
        }
    }

    /**
     * 测试 4：使用 askUser(true) 快捷构建。
     *
     * <p>验证 askUser(true) 自动注册 AskUserTool + PauseAdvisor，
     * 效果与手动注册一致。
     */
    public static void testAskUserShorthand() {
        TestConfig.printTestHeader("测试 4：askUser(true) 快捷构建");

        ChatModel chatModel = TestConfig.createChatModel();

        // askUser(true) 自动注册 AskUserTool + PauseAdvisor
        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .askUser(true)
                .maxRounds(10)
                .build();

        String query = "帮我写一封邮件，主题是给客户道歉";
        System.out.println("Q: " + query);

        AgentResult result = agent.callForResult(query, RunnableParams.empty());

        int pauseRound = 0;
        while (result instanceof AgentResult.Paused p) {
            pauseRound++;
            System.out.println("\n--- Agent 暂停（第 " + pauseRound + " 次提问）---");
            printPendingToolCalls(p.state());

            Map<String, String> answers = new LinkedHashMap<>();
            for (PendingToolCall ptc : p.state().getPendingToolCalls()) {
                String mockAnswer = mockUserAnswer(pauseRound);
                System.out.println("用户回答: " + mockAnswer);
                answers.put(ptc.id(), mockAnswer);
            }

            System.out.println("\n--- 恢复 Agent 执行 ---");
            result = agent.resume(p.state(), answers);
        }

        if (result instanceof AgentResult.Completed c) {
            System.out.println("A: " + c.answer());
            System.out.println("\n（共暂停 " + pauseRound + " 次）");
        }
    }

    /**
     * 测试 5：流式 + 文件工具 + 多工具拦截 + 交互式确认。
     *
     * <p>场景：Agent 执行文件操作前需要人工确认，同时可通过 ask_user 提问。
     * PauseAdvisor 同时拦截 ask_user 和文件写入工具，模拟生产环境中的敏感操作审批。
     */
    public static void testStreamWithFileApproval() {
        TestConfig.printTestHeader("测试 5：流式 + 文件操作审批 — 交互式");

        ChatModel chatModel = TestConfig.createChatModel();
        Scanner scanner = new Scanner(System.in);

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(FileSystemTools.create())
                .tools(AskUserTool.create())
                .advisors(new PauseAdvisor("ask_user", "write_file"))
                .maxRounds(20)
                .build();

        System.out.print("请输入你的问题: ");
        String query = scanner.nextLine();
        System.out.println();
        System.out.print("A: ");

        PauseState pauseState = TestConfig.collectStreamEvents(agent.streamForResult(query, RunnableParams.empty()));
        System.out.println();

        int pauseRound = 0;
        while (pauseState != null) {
            pauseRound++;
            System.out.println("\n--- Agent 暂停（第 " + pauseRound + " 次）---");
            printPendingToolCalls(pauseState);

            Map<String, String> answers = new LinkedHashMap<>();
            for (PendingToolCall ptc : pauseState.getPendingToolCalls()) {
                System.out.print("请输入回答（确认输入 ok，拒绝请说明原因）: ");
                String answer = scanner.nextLine();
                answers.put(ptc.id(), answer);
            }

            System.out.println("\n--- 恢复 Agent 执行 ---");
            System.out.print("A: ");
            pauseState = TestConfig.collectStreamEvents(agent.resumeStream(pauseState, answers));
            System.out.println();
        }

        System.out.println("（共暂停 " + pauseRound + " 次）");
    }

    /**
     * 测试 6：自定义 askUserTool + 流式 + 文件审批。
     *
     * <p>演示自定义用户输入工具的完整流程：
     * <ul>
     *   <li>自定义工具通过 {@code tools()} 注册，支持 List 参数展示选项列表</li>
     *   <li>PauseAdvisor 区分审批工具（write_file）和用户输入工具（custom_ask）</li>
     *   <li>用户输入工具的回答直接作为工具结果，审批工具需确认后执行</li>
     * </ul>
     */
    public static void testCustomAskUserTool() {
        TestConfig.printTestHeader("测试 6：自定义 askUserTool + 流式 + 文件审批");

        ChatModel chatModel = TestConfig.createZhiPuChatModel();
        Scanner scanner = new Scanner(System.in);

        ToolCallback customAskTool = ToolCallbacks.from(new CustomAskTool())[0];

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(FileSystemTools.create())
                .askUser(true)
//                .tools(customAskTool)           // 自定义 ask 工具通过 tools() 注册
                .advisors(PauseAdvisor.builder()
                        .approvalTools("write_file")
//                        .askUserTool("custom_ask")
                        .build())
                .maxRounds(20)
                .build();

        System.out.print("请输入你的问题: ");
        String query = scanner.nextLine();
        System.out.println();
        System.out.print("A: ");

        PauseState pauseState = TestConfig.collectStreamEvents(agent.streamForResult(query, RunnableParams.empty()));
        System.out.println();

        int pauseRound = 0;
        while (pauseState != null) {
            pauseRound++;
            System.out.println("\n--- Agent 暂停（第 " + pauseRound + " 次）---");
            printPendingToolCalls(pauseState);

            Map<String, String> answers = new LinkedHashMap<>();
            for (PendingToolCall ptc : pauseState.getPendingToolCalls()) {
                System.out.print("请输入回答（确认操作输入 ok，拒绝请说明原因）: ");
                String answer = scanner.nextLine();
                answers.put(ptc.id(), answer);
            }

            System.out.println("\n--- 恢复 Agent 执行 ---");
            System.out.print("A: ");
            pauseState = TestConfig.collectStreamEvents(agent.resumeStream(pauseState, answers));
            System.out.println();
        }

        System.out.println("（共暂停 " + pauseRound + " 次）");
    }

    // ==================== 自定义 Ask 工具示例 ====================

    /**
     * 自定义用户输入工具 — 带选项列表的提问工具。
     * <p>
     * 演示调用方如何创建自定义 ask 工具：
     * - 支持 List&lt;String&gt; 参数，可向用户展示选项列表
     * - 工具名称为 "custom_ask"（通过 @Tool name 指定）
     * - 注册后用户回答直接作为工具结果，不需要框架执行
     */
    static class CustomAskTool {

        @Tool(name = "custom_ask", description = """
                必须主动向用户提问并提供选项列表供选择。

                使用说明：
                - 在 question 参数中描述问题
                - 在 options 参数中提供可选方案列表
                - 如果有推荐方案，在描述中标注"(推荐)"
                """)
        public String customAsk(
                @ToolParam(description = "要问用户的问题") String question,
                @ToolParam(description = "供用户选择的选项列表") List<String> options) {
            return "此工具需要配合 PauseAdvisor 使用";
        }
    }

    // ===== 辅助方法 =====

    private static void printPendingToolCalls(PauseState state) {
        for (PendingToolCall ptc : state.getPendingToolCalls()) {
            System.out.println("Agent 请求执行 [" + ptc.name() + "] (id=" + ptc.id() + "):");
            System.out.println("  " + ptc.arguments());
        }
    }

    /**
     * 根据暂停轮次模拟不同的用户回答。
     */
    private static String mockUserAnswer(int round) {
        return switch (round) {
            case 1 -> "我喜欢海边，预算5000元左右";
            case 2 -> "5天行程，两个人";
            case 3 -> "希望住海景房，喜欢安静的地方";
            default -> "没有其他要求了，你推荐吧";
        };
    }

    // ===== Main =====

    public static void main(String[] args) {
        System.out.println("===============================================");
        System.out.println("       Human-in-the-Loop Test");
        System.out.println("===============================================");
        System.out.println("ChatModel: " + TestConfig.CHAT_MODEL);
        System.out.println("===============================================");

        int testNumber = 6;

        switch (testNumber) {
            case 1 -> testCallPauseResume();
            case 2 -> testStreamPauseResume();
            case 3 -> testCallInteractive();
            case 4 -> testAskUserShorthand();
            case 5 -> testStreamWithFileApproval();
            case 6 -> testCustomAskUserTool();
            default -> System.out.println("无效的测试编号: " + testNumber);
        }

        System.out.println("\n===============================================");
        System.out.println("       Test Completed");
        System.out.println("===============================================");
    }
}
