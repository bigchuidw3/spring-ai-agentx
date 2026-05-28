package com.agentx.ai.samples;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.advisors.PauseAdvisor;
import com.agentx.ai.core.model.*;
import com.agentx.ai.core.tools.BashTool;
import com.agentx.ai.core.tools.FileSystemTools;
import com.agentx.ai.core.tools.GrepTool;
import com.agentx.ai.core.tools.SkillsTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.*;
import java.util.stream.Collectors;

import static com.agentx.ai.core.utils.ToolMergeUtil.mergeTools;

/**
 * 多阶段输出测试。
 *
 * <p>测试内容：
 * <ul>
 *   <li>测试 1：流式 — 完整事件流（AgentStart → Text → ToolStart/ToolEnd → StageOutput → Complete）</li>
 *   <li>测试 2：非流式 — callForResult 获取 stageOutputs</li>
 *   <li>测试 3：多 Provider — AFTER_START + AFTER_TOOL_END + BEFORE_COMPLETE 组合</li>
 *   <li>测试 4：ThinkTag — 开启 think 标签解析</li>
 *   <li>测试 5：无工具场景 — 纯文本输出的 BEFORE_COMPLETE 阶段</li>
 *   <li>测试 6：Skills + Human-in-the-Loop + 流式多阶段输出</li>
 * </ul>
 *
 * @author bigchui
 *
 */
public class StageOutputTest {

    // ===== 自定义 StageOutputProvider 实现 =====

    /**
     * 引用链接 Provider — 在最终答案后提取工具调用中的引用信息。
     */
    public static class ReferenceProvider implements StageOutputProvider {
        @Override
        public String name() {
            return "reference";
        }

        @Override
        public StageTiming timing() {
            return StageTiming.BEFORE_COMPLETE;
        }

        @Override
        public Object produce(StageContext context) {
            if (context.toolRecords().isEmpty()) {
                return null;
            }
            // 模拟提取引用：将所有工具名和返回结果摘要作为引用
            List<Map<String, String>> refs = context.toolRecords().stream()
                    .map(r -> Map.of(
                            "tool", r.toolName(),
                            "summary", r.result() != null && r.result().length() > 50
                                    ? r.result().substring(0, 50) + "..."
                                    : r.result()
                    ))
                    .collect(Collectors.toList());
            return refs;
        }
    }

    /**
     * 推荐问题 Provider — 在最终答案后基于 query 生成推荐。
     */
    public static class RecommendProvider implements StageOutputProvider {
        @Override
        public String name() {
            return "recommend";
        }

        @Override
        public StageTiming timing() {
            return StageTiming.BEFORE_COMPLETE;
        }

        @Override
        public Object produce(StageContext context) {
            // 简单示例：基于 query 生成推荐问题
            String query = context.query();
            if (query == null || query.isEmpty()) {
                return null;
            }
            return List.of(
                    "继续深入了解：" + query,
                    "相关主题推荐",
                    "其他常见问题"
            );
        }
    }

    /**
     * 工具摘要 Provider — 每次工具执行后实时输出摘要。
     */
    public static class ToolSummaryProvider implements StageOutputProvider {
        @Override
        public String name() {
            return "tool_summary";
        }

        @Override
        public StageTiming timing() {
            return StageTiming.AFTER_TOOL_END;
        }

        @Override
        public Object produce(StageContext context) {
            ToolRecord last = context.lastToolRecord();
            if (last == null) {
                return null;
            }
            return Map.of(
                    "tool", last.toolName(),
                    "status", "completed",
                    "resultLength", last.result() != null ? last.result().length() : 0
            );
        }
    }

    /**
     * 欢迎语 Provider — 在 Agent 启动后输出欢迎信息。
     */
    public static class WelcomeProvider implements StageOutputProvider {
        @Override
        public String name() {
            return "welcome";
        }

        @Override
        public StageTiming timing() {
            return StageTiming.AFTER_START;
        }

        @Override
        public Object produce(StageContext context) {
            return Map.of("message", "Agent 开始处理您的请求", "query", context.query());
        }
    }

    // ===== 测试方法 =====

    /**
     * 测试 1：流式 — 完整事件流。
     *
     * <p>展示所有事件类型：AgentStart → Text → ToolStart/ToolEnd → StageOutput → Complete。
     * 注册 ReferenceProvider + RecommendProvider（BEFORE_COMPLETE 时机）。
     */
    public static void testStreamFullEvents() {
        TestConfig.printTestHeader("测试 1：流式 — 完整事件流（含工具调用）");

        ChatModel chatModel = TestConfig.createChatModel();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(FileSystemTools.create())
                .stageOutputProviders(new ReferenceProvider(), new RecommendProvider())
                .maxRounds(5)
                .build();

        String query = "读取当前目录下的 pom.xml 文件内容，简要说明这是什么项目";
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

        // 统计各类型事件数量
        System.out.println("\n事件统计:");
        long textCount = allEvents.stream().filter(e -> e instanceof AgentStreamEvent.Text).count();
        long toolStartCount = allEvents.stream().filter(e -> e instanceof AgentStreamEvent.ToolStart).count();
        long toolEndCount = allEvents.stream().filter(e -> e instanceof AgentStreamEvent.ToolEnd).count();
        long stageOutputCount = allEvents.stream().filter(e -> e instanceof AgentStreamEvent.StageOutput).count();
        long completeCount = allEvents.stream().filter(e -> e instanceof AgentStreamEvent.Complete).count();

        System.out.println("  Text: " + textCount);
        System.out.println("  ToolStart: " + toolStartCount);
        System.out.println("  ToolEnd: " + toolEndCount);
        System.out.println("  StageOutput: " + stageOutputCount);
        System.out.println("  Complete: " + completeCount);

        // 打印 StageOutput 详情
        allEvents.stream()
                .filter(e -> e instanceof AgentStreamEvent.StageOutput)
                .map(e -> (AgentStreamEvent.StageOutput) e)
                .forEach(so -> System.out.println("  Stage[" + so.stage() + "]: " + so.data()));
    }

    /**
     * 测试 2：非流式 — callForResult 获取 stageOutputs。
     *
     * <p>验证非流式调用中，AgentResult.Completed 包含 stageOutputs Map。
     */
    public static void testCallWithStageOutputs() {
        TestConfig.printTestHeader("测试 2：非流式 — callForResult + stageOutputs");

        ChatModel chatModel = TestConfig.createChatModel();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(FileSystemTools.create())
                .stageOutputProviders(new ReferenceProvider(), new RecommendProvider())
                .maxRounds(5)
                .build();

        String query = "读取 pom.xml 文件的第一行内容";
        System.out.println("Q: " + query);

        AgentResult result = agent.callForResult(query, RunnableParams.empty());

        if (result instanceof AgentResult.Completed c) {
            System.out.println("A: " + c.answer());
            System.out.println("\nStage Outputs:");
            Map<String, Object> stageOutputs = c.stageOutputs();
            if (stageOutputs.isEmpty()) {
                System.out.println("  (无阶段输出)");
            } else {
                stageOutputs.forEach((stage, data) ->
                        System.out.println("  [" + stage + "] " + data));
            }
        } else if (result instanceof AgentResult.Paused p) {
            System.out.println("Agent 暂停（此场景不应暂停）");
        }
    }

    /**
     * 测试 3：多 Provider — AFTER_START + AFTER_TOOL_END + BEFORE_COMPLETE 组合。
     *
     * <p>同时注册 3 个不同时机的 Provider，验证每个钩子点都被正确触发。
     */
    public static void testMultipleTimings() {
        TestConfig.printTestHeader("测试 3：多 Provider — 三种时机组合");

        ChatModel chatModel = TestConfig.createChatModel();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(FileSystemTools.create())
                .stageOutputProviders(
                        new WelcomeProvider(),        // AFTER_START
                        new ToolSummaryProvider(),    // AFTER_TOOL_END
                        new ReferenceProvider()       // BEFORE_COMPLETE
                )
                .maxRounds(5)
                .build();

        String query = "帮我查看当前目录下有什么文件";
        System.out.println("Q: " + query);
        System.out.println("--- Events Start ---");

        agent.streamForResult(query, RunnableParams.empty())
                .doOnNext(TestConfig::printEvent)
                .doOnError(err -> System.err.println("Error: " + err.getMessage()))
                .blockLast();

        System.out.println("--- Events End ---");
    }

    /**
     * 测试 4：ThinkTag — 开启 think 标签解析。
     *
     * <p>启用 thinkTagEnabled 后，LLM 输出中的 think  /think 内容
     * 会被拆分为 Thinking 事件，标签外为 Text 事件。
     * 使用 DeepSeek 等模型时效果明显（Qwen 模型不会输出 think 标签，此测试仅验证开关不影响正常输出）。
     */
    public static void testThinkTagEnabled() {
        TestConfig.printTestHeader("测试 4：ThinkTag 开关 — thinkTagEnabled=true");

        ChatModel chatModel = TestConfig.createChatModel();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .stageOutputProviders(new RecommendProvider())
                .thinkTagEnabled(true)
                .maxRounds(3)
                .build();

        String query = "简单介绍下 Java 的特点";
        System.out.println("Q: " + query);
        System.out.println("--- Events Start ---");

        List<AgentStreamEvent> events = new ArrayList<>();
        agent.streamForResult(query, RunnableParams.empty())
                .doOnNext(event -> {
                    events.add(event);
                    TestConfig.printEvent(event);
                })
                .doOnError(err -> System.err.println("Error: " + err.getMessage()))
                .blockLast();

        System.out.println("--- Events End ---");

        long thinkingCount = events.stream().filter(e -> e instanceof AgentStreamEvent.Thinking).count();
        System.out.println("\nThinking events: " + thinkingCount);
        System.out.println("（Qwen 不输出 think 标签，Thinking 事件数为 0 属正常）");
    }

    /**
     * 测试 5：无工具场景 — 纯文本输出的 BEFORE_COMPLETE 阶段。
     *
     * <p>不注册任何工具，Agent 直接回答问题，
     * 验证 StageOutput 在无工具调用时仍能正常触发（BEFORE_COMPLETE 时机）。
     */
    public static void testNoToolsWithStageOutput() {
        TestConfig.printTestHeader("测试 5：无工具 — 纯文本 + BEFORE_COMPLETE 阶段");

        ChatModel chatModel = TestConfig.createChatModel();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .stageOutputProviders(new RecommendProvider())
                .maxRounds(3)
                .build();

        String query = "用三句话介绍 Spring 框架";
        System.out.println("Q: " + query);
        System.out.println("--- Events Start ---");

        List<AgentStreamEvent> events = new ArrayList<>();
        agent.streamForResult(query, RunnableParams.empty())
                .doOnNext(event -> {
                    events.add(event);
                    TestConfig.printEvent(event);
                })
                .doOnError(err -> System.err.println("Error: " + err.getMessage()))
                .blockLast();

        System.out.println("--- Events End ---");

        // 验证有 StageOutput 事件
        boolean hasStageOutput = events.stream()
                .anyMatch(e -> e instanceof AgentStreamEvent.StageOutput);
        System.out.println("\n包含 StageOutput 事件: " + hasStageOutput);
    }

    /**
     * 测试 6：Skills + Human-in-the-Loop + 流式多阶段输出。
     *
     * <p>综合测试：Agent 同时具备文件工具、Skills、ask_user，
     * 流式输出包含完整事件流（AgentStart → Text → ToolStart/ToolEnd → StageOutput → Paused → Complete）。
     *
     * <p>流程：
     * 1. 用户提问，Agent 调用工具处理
     * 2. Agent 通过 ask_user 向用户追问
     * 3. 用户回答后 resume，Agent 继续执行
     * 4. 最终输出答案 + StageOutput（reference + recommend）
     */
    public static void testSkillsWithHitlAndStageOutput() {
        TestConfig.printTestHeader("测试 6：Skills + Human-in-the-Loop + 流式多阶段输出");

        ChatModel chatModel = TestConfig.createChatModel();
        Scanner scanner = new Scanner(System.in);

        // 注册工具：文件系统 + Skills + ask_user
        ToolCallback[] allTools = mergeTools(
                BashTool.create(),
                FileSystemTools.create(),
                GrepTool.create(),
                new ToolCallback[]{SkillsTool.builder().addSkillsDirectory(TestConfig.SKILLS_DIR).build()}
        );

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(allTools)
                .askUser(true)      // 自动注册 PauseAdvisor("ask_user") + AskUserTool
                .thinkingMode(ThinkingMode.REASONING_CONTENT)
                .stageOutputProviders(
                        new ToolSummaryProvider(),    // AFTER_TOOL_END — 每次工具执行后输出摘要
                        new ReferenceProvider(),      // BEFORE_COMPLETE — 提取引用
                        new RecommendProvider()       // BEFORE_COMPLETE — 推荐问题
                )
                .advisors(new PauseAdvisor("write_file"))
                .maxRounds(50)
                .maxRetries(3)
                .build();

        System.out.print("请输入你的问题: ");
        String query = scanner.nextLine();
        System.out.println("--- Events Start ---");

        // 首次流式调用
        PauseState pauseState = TestConfig.collectStreamEvents(agent.streamForResult(query, RunnableParams.empty()));
        System.out.println();

        // 循环处理暂停（ask_user / read_file / write_file）
        int pauseRound = 0;
        while (pauseState != null) {
            pauseRound++;
            System.out.println("\n--- Agent 暂停（第 " + pauseRound + " 次）---");
            for (PendingToolCall ptc : pauseState.getPendingToolCalls()) {
                System.out.println("[" + ptc.name() + "] " + ptc.arguments());
            }

            Map<String, String> answers = new LinkedHashMap<>();
            for (PendingToolCall ptc : pauseState.getPendingToolCalls()) {
                if ("ask_user".equals(ptc.name())) {
                    System.out.print("你的回答: ");
                } else {
                    // 文件操作审批：输入 ok 确认，否则拒绝
                    System.out.print("确认执行 " + ptc.name() + "（ok/拒绝）: ");
                }
                String answer = scanner.nextLine();
                answers.put(ptc.id(), answer);
            }

            System.out.println("\n--- 恢复 Agent 执行 ---");
            pauseState = TestConfig.collectStreamEvents(agent.resumeStream(pauseState, answers));
            System.out.println();
        }

        System.out.println("--- Events End ---");
        System.out.println("（共暂停 " + pauseRound + " 次）");
    }

    // ===== Main =====

    public static void main(String[] args) {
        System.out.println("===============================================");
        System.out.println("       Multi-Stage Output Test");
        System.out.println("===============================================");
        System.out.println("ChatModel: " + TestConfig.CHAT_MODEL);
        System.out.println("===============================================");

        int testNumber = 6;

        switch (testNumber) {
            case 1 -> testStreamFullEvents();
            case 2 -> testCallWithStageOutputs();
            case 3 -> testMultipleTimings();
            case 4 -> testThinkTagEnabled();
            case 5 -> testNoToolsWithStageOutput();
            case 6 -> testSkillsWithHitlAndStageOutput();
            default -> System.out.println("无效的测试编号: " + testNumber);
        }

        System.out.println("\n===============================================");
        System.out.println("       Test Completed");
        System.out.println("===============================================");
    }
}
