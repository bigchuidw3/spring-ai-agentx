package com.agentx.ai.samples;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.model.AgentResult;
import com.agentx.ai.core.model.AgentStreamEvent;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.tools.BashTool;
import com.agentx.ai.core.tools.FileSystemTools;
import com.agentx.ai.core.tools.TodoWriteTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;

import static com.agentx.ai.core.utils.ToolMergeUtil.mergeTools;

/**
 * TodoWriteTool 测试。
 *
 * <p>测试内容：
 * <ul>
 *   <li>测试 1：流式 — 多步骤任务的 TodoProgress 事件流</li>
 *   <li>测试 2：非流式 — 验证 TodoWrite 在 callForResult 中正常工作</li>
 *   <li>测试 3：纯 TodoWrite — 无其他工具，仅任务列表管理</li>
 * </ul>
 *
 * @author bigchui
 */
public class TodoWriteTest {

    /**
     * 测试 1：流式 — 多步骤任务 + TodoProgress 事件。
     *
     * <p>注册 FileSystemTools + BashTool + TodoWriteTool，
     * 给出需要多步骤的任务，观察：
     * - LLM 是否主动调用 TodoWrite 创建任务列表
     * - TodoProgress 事件是否正确发射
     * - 任务状态是否逐步更新（pending → in_progress → completed）
     */
    public static void testStreamWithTodoWrite() {
        TestConfig.printTestHeader("测试 1：流式 — 多步骤任务 + TodoProgress 事件");

        ChatModel chatModel = TestConfig.createChatModel();

        ToolCallback[] allTools = mergeTools(
                TodoWriteTool.create(),
                BashTool.create(),
                FileSystemTools.create()
        );

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(allTools)
                .maxRounds(30)
                .build();

        String query = "帮我完成以下任务："
                + "1. 列出当前目录下的所有 java 文件 "
                + "2. 找到其中最大的那个文件 "
                + "3. 读取该文件的前 20 行内容 "
                + "4. 总结这个文件的功能";
        System.out.println("Q: " + query);
        System.out.println("--- Events Start ---");

        agent.streamForResult(query, RunnableParams.empty())
                .doOnNext(event -> {
                    TestConfig.printEvent(event);
                })
                .doOnError(err -> System.err.println("Error: " + err.getMessage()))
                .blockLast();

        System.out.println("--- Events End ---");
    }

    /**
     * 测试 2：非流式 — callForResult 中 TodoWrite 正常工作。
     *
     * <p>验证非流式调用中，TodoWrite 工具能被正常调用和执行。
     */
    public static void testCallWithTodoWrite() {
        TestConfig.printTestHeader("测试 2：非流式 — callForResult + TodoWrite");

        ChatModel chatModel = TestConfig.createChatModel();

        ToolCallback[] allTools = mergeTools(
                TodoWriteTool.create(),
                BashTool.create(),
                FileSystemTools.create()
        );

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(allTools)
                .maxRounds(30)
                .build();

        String query = "帮我查看当前目录结构，列出 src 目录下的所有 Java 文件，然后总结项目结构";
        System.out.println("Q: " + query);

        AgentResult result = agent.callForResult(query, RunnableParams.empty());

        if (result instanceof AgentResult.Completed c) {
            System.out.println("A: " + c.answer());
        } else if (result instanceof AgentResult.Failed f) {
            System.out.println("Failed: " + f.error());
        }
    }

    /**
     * 测试 3：纯 TodoWrite — 无其他工具，仅任务列表管理。
     *
     * <p>不注册任何文件/命令工具，只注册 TodoWrite。
     * LLM 应该能在没有实际执行能力的情况下，仍然使用 TodoWrite 来组织任务。
     * 这个测试验证 LLM 能正确调用 TodoWrite 并触发 TodoProgress 事件。
     */
    public static void testTodoWriteOnly() {
        TestConfig.printTestHeader("测试 3：纯 TodoWrite — 仅任务列表管理");

        ChatModel chatModel = TestConfig.createChatModel();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(TodoWriteTool.create())
                .maxRounds(10)
                .build();

        String query = "我需要制定一个学习 Spring Boot 的计划。"
                + "请帮我创建一个包含 5 个步骤的任务列表，从基础到进阶。"
                + "使用 TodoWrite 工具来管理这些任务。";
        System.out.println("Q: " + query);
        System.out.println("--- Events Start ---");

        List<AgentStreamEvent> allEvents = new ArrayList<>();
        agent.streamForResult(query, RunnableParams.empty())
                .doOnNext(event -> {
                    allEvents.add(event);
                    TestConfig.printEvent(event);
                })
                .doOnError(err -> System.err.println("Error: " + err.getMessage()))
                .blockLast();

        System.out.println("--- Events End ---");

        long todoProgressCount = allEvents.stream()
                .filter(e -> e instanceof AgentStreamEvent.TodoProgress)
                .count();
        System.out.println("\nTodoProgress 事件数: " + todoProgressCount);
    }

    // ===== Main =====

    public static void main(String[] args) {
        System.out.println("===============================================");
        System.out.println("       TodoWriteTool Test");
        System.out.println("===============================================");
        System.out.println("ChatModel: " + TestConfig.CHAT_MODEL);
        System.out.println("===============================================");

        int testNumber = 1;

        switch (testNumber) {
            case 1 -> testStreamWithTodoWrite();
            case 2 -> testCallWithTodoWrite();
            case 3 -> testTodoWriteOnly();
            default -> System.out.println("无效的测试编号: " + testNumber);
        }

        System.out.println("\n===============================================");
        System.out.println("       Test Completed");
        System.out.println("===============================================");
    }
}
