package com.agentx.ai.core.tools;

import com.agentx.ai.core.advisors.PauseAdvisor;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Scanner;

/**
 * 问用户工具 -- 让 Agent 能主动向用户提问。
 *
 * <p>两种使用模式：
 * <ul>
 *   <li>HITL 模式：配合 {@link PauseAdvisor} 使用，
 *       工具被拦截不执行，问题带出给调用方，无线程阻塞。适用于 Web 服务场景。</li>
 *   <li>CLI 模式：直接注册 {@link #createBlocking()} 创建的工具，
 *       使用 Scanner 阻塞等待控制台输入。适用于 CLI / 桌面应用。</li>
 * </ul>
 *
 * <p>HITL 模式示例：
 * <pre>{@code
 * ReactAgent agent = ReactAgent.builder()
 *     .chatModel(chatModel)
 *     .askUser(true)
 *     .build();
 *
 * AgentResult result = agent.callForResult("帮我推荐旅行目的地");
 * if (result instanceof AgentResult.Paused p) {
 *     // 从 p.state().getPendingToolCalls() 提取问题
 *     // 收集用户答案后:
 *     agent.resume(p.state(), Map.of(toolCallId, userAnswer));
 * }
 * }</pre>
 *
 * @author bigchui
 * 
 */
public class AskUserTool {

    @Tool(name = "ask_user", description = """
            向用户提问。在给出任何计划、方案、推荐、建议之前，必须先调用此工具了解用户的具体情况和偏好，禁止直接给出通用回答。

            必须提问的场景：
            1. 制定计划、方案、策略（健身、旅行、学习、开发等）
            2. 给出推荐或建议（产品、技术选型、方案选择等）
            3. 用户指令模糊，存在多种理解方式
            4. 需要用户在多个选项中做出决策

            参数使用：
            - question：必填，清晰描述要问用户的问题
            - options：可选，当问题有明确选项时提供（如方案选择、偏好筛选），开放性问题不需要提供
            - 如果某个选项你更推荐，在选项文本中标注"(推荐)"
            """)
    public String askUser(
            @ToolParam(description = "要问用户的问题") String question,
            @ToolParam(description = "供用户选择的选项列表，开放性问题可不传", required = false) List<String> options) {
        return "此工具需要配合 PauseAdvisor 使用，或者使用 createBlocking() 创建 CLI 版本";
    }

    /**
     * HITL 模式：创建不阻塞的 AskUserTool。
     *
     * 需要配合 {@link PauseAdvisor} 使用。
     */
    public static ToolCallback[] create() {
        return ToolCallbacks.from(new AskUserTool());
    }

    /**
     * CLI 模式：创建阻塞式的 AskUserTool，使用 Scanner 等待控制台输入。
     *
     * 适用于 CLI / 桌面应用，不适用于 Web 服务。
     */
    public static ToolCallback[] createBlocking() {
        return ToolCallbacks.from(new BlockingAskUserTool());
    }

    /**
     * CLI 阻塞式实现。
     */
    static class BlockingAskUserTool {

        private final Scanner scanner = new Scanner(System.in);

        @Tool(name = "ask_user", description = """
                向用户提问。在给出任何计划、方案、推荐、建议之前，必须先调用此工具了解用户的具体情况和偏好，禁止直接给出通用回答。

                必须提问的场景：
                1. 制定计划、方案、策略（健身、旅行、学习、开发等）
                2. 给出推荐或建议（产品、技术选型、方案选择等）
                3. 用户指令模糊，存在多种理解方式
                4. 需要用户在多个选项中做出决策

                参数使用：
                - question：必填，清晰描述要问用户的问题
                - options：可选，当问题有明确选项时提供（如方案选择、偏好筛选），开放性问题不需要提供
                - 如果某个选项你更推荐，在选项文本中标注"(推荐)"
                """)
        public String askUser(
                @ToolParam(description = "要问用户的问题") String question,
                @ToolParam(description = "供用户选择的选项列表，开放性问题可不传", required = false) List<String> options) {
            System.out.println("\n=== AI 提问 ===");
            System.out.println(question);
            if (options != null && !options.isEmpty()) {
                System.out.println("选项：");
                for (int i = 0; i < options.size(); i++) {
                    System.out.println("  " + (i + 1) + ". " + options.get(i));
                }
            }
            System.out.print("请输入回答: ");

            String answer = scanner.nextLine();
            System.out.println("==============\n");
            return answer;
        }
    }
}
