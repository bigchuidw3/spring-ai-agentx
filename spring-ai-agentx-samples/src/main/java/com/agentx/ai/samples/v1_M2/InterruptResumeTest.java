package com.agentx.ai.samples.v1_M2;

import com.agentx.ai.core.advisors.PauseAdvisor;
import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.interrupt.InMemoryPauseStateStore;
import com.agentx.ai.core.interrupt.JdbcPauseStateStore;
import com.agentx.ai.core.interrupt.PauseReason;
import com.agentx.ai.core.model.AgentResult;
import com.agentx.ai.core.model.PauseState;
import com.agentx.ai.core.model.PendingToolCall;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.tools.AskUserTool;
import com.agentx.ai.samples.TestConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 中断与恢复测试。
 *
 * <p>覆盖用户主动中断 + 断点恢复场景，与 {@link HumanInTheLoopTest} 的 HITL 场景互补。
 *
 * <p>测试内容：
 * <ul>
 *   <li>测试 1：流式中断（首轮 LLM 生成中触发 interrupt）— 验证 Paused 事件携带 USER_INTERRUPT</li>
 *   <li>测试 2：工具执行中中断 — 验证 pendingToolCalls 携带 call_xxx，且 safePoint=TOOL_EXECUTION</li>
 *   <li>测试 3：从中断状态恢复 — resumeStream(String) 自动从 stateStore 取回并恢复</li>
 *   <li>测试 4：HITL 与 Interrupt 共存 — 同一会话先 HITL 暂停恢复，再主动中断</li>
 *   <li>测试 5：discardInterruptedState — 放弃恢复后 stateStore 应清空</li>
 *   <li>测试 6：hasInterruptedState / getInterruptedState 元数据检查</li>
 *   <li>测试 7：非流式 Interrupt — callForResult + interrupt → Paused → resume</li>
 *   <li>测试 8：非流式 HITL — callForResult → Paused → resume</li>
 * </ul>
 *
 * <p>用法：调整 {@code main} 中的 {@code testNumber} 选择测试。
 *
 * @author bigchui
 */
public class InterruptResumeTest {

    // ==================== 测试方法 ====================

    /**
     * 测试 1：流式生成中中断。
     *
     * <p>启动流式后延迟 1.5 秒触发 interrupt，预期 LLM 还在生成时收到 Paused 事件，
     * 暂停原因应为 USER_INTERRUPT，safePoint 为 LLM_STREAMING。
     */
    public static void test1_InterruptDuringLlmStreaming() {
        TestConfig.printTestHeader("测试 1：LLM 流式生成中中断");

        ChatModel chatModel = TestConfig.createDeepSeekV4ChatModel();
        InMemoryPauseStateStore stateStore = new InMemoryPauseStateStore();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .stateStore(stateStore)
                .maxRounds(5)
                .build();

        String conversationId = "conv-test1-" + UUID.randomUUID();
        RunnableParams params = RunnableParams.builder().conversationId(conversationId).build();

        String query = "请用 500 字详细介绍 Java 的并发编程（让生成时间足够长）";
        System.out.println("Q: " + query);
        System.out.print("A: ");

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(() -> {
            System.out.println("\n[10s 后] 触发 interrupt...");
            boolean ok = agent.interrupt(conversationId, "用户主动中断");
            System.out.println("[interrupt 返回] " + ok);
        }, 10000, TimeUnit.MILLISECONDS);

        PauseState state = TestConfig.collectStreamEvents(agent.streamForResult(query, params));
        scheduler.shutdown();

        System.out.println("\n--- 中断结果分析 ---");
        if (state == null) {
            System.out.println("❌ 未捕获 Paused 事件（可能 LLM 在 1.5s 内已完成，请调大延迟）");
            return;
        }
        System.out.println("reason          = " + state.getReason()
                + (state.getReason() == PauseReason.USER_INTERRUPT ? " ✅" : " ❌"));
        System.out.println("safePoint  = " + state.getSafePoint());
        System.out.println("currentRound    = " + state.getCurrentRound());
        System.out.println("interruptMessage= " + state.getInterruptMessage());
        System.out.println("pendingTools    = " + (state.getPendingToolCalls() == null ? 0 : state.getPendingToolCalls().size()));

        System.out.println("\n--- stateStore 状态 ---");
        System.out.println("hasInterruptedState = " + agent.hasInterruptedState(conversationId));
    }

    /**
     * 测试 2：工具执行中中断。
     *
     * <p>使用一个耗时 5 秒的工具，interrupt 在 1.5 秒后触发。
     * 预期：safePoint=TOOL_EXECUTION，pendingToolCalls 含 slow_task 工具。
     */
    public static void test2_InterruptDuringToolExecution() {
        TestConfig.printTestHeader("测试 2：工具执行中中断");

        ChatModel chatModel = TestConfig.createDeepSeekV4ChatModel();
        InMemoryPauseStateStore stateStore = new InMemoryPauseStateStore();
        SlowTool slowTool = new SlowTool();
        var toolCallbacks = ToolCallbacks.from(slowTool);

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(toolCallbacks)
                .stateStore(stateStore)
                .maxRounds(5)
                .build();

        String conversationId = "conv-test2-" + UUID.randomUUID();
        RunnableParams params = RunnableParams.builder().conversationId(conversationId).build();

        String query = "请调用 slow_task 工具，然后告诉我完成";
        System.out.println("Q: " + query);
        System.out.print("A: ");

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(() -> {
            System.out.println("\n[5s 后] 工具执行中触发 interrupt...");
            boolean ok = agent.interrupt(conversationId, "用户中断工具执行");
            System.out.println("[interrupt 返回] " + ok);
        }, 5000, TimeUnit.MILLISECONDS);

        PauseState state = TestConfig.collectStreamEvents(agent.streamForResult(query, params));
        scheduler.shutdown();

        System.out.println("\n--- 中断结果分析 ---");
        if (state == null) {
            System.out.println("❌ 未捕获 Paused 事件");
            return;
        }
        System.out.println("reason          = " + state.getReason()
                + (state.getReason() == PauseReason.USER_INTERRUPT ? " ✅" : ""));
        System.out.println("safePoint  = " + state.getSafePoint()
                + (state.getSafePoint() != null ? " ✅" : ""));
        System.out.println("currentRound    = " + state.getCurrentRound());
        System.out.println("pendingToolCalls= " + state.getPendingToolCalls());
        System.out.println("\n注：工具可能已部分执行（slow_task 在后台继续跑），这是分布式系统正常现象");
    }

    /**
     * 测试 3：从中断状态恢复。
     *
     * <p>测试 1 流程结束后，调用 resumeStream(conversationId) 从 stateStore 取回 PauseState，
     * Agent 应继续完成回答。
     */
    public static void test3_ResumeAfterInterrupt() {
        TestConfig.printTestHeader("测试 3：从中断状态恢复");

        ChatModel chatModel = TestConfig.createDeepSeekV4ChatModel();
        InMemoryPauseStateStore stateStore = new InMemoryPauseStateStore();

        SlowTool slowTool = new SlowTool();
        var toolCallbacks = ToolCallbacks.from(slowTool);

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .stateStore(stateStore)
                .maxRounds(5)
                .tools(toolCallbacks)
                .build();

        String conversationId = "conv-test3-" + UUID.randomUUID();
        RunnableParams params = RunnableParams.builder().conversationId(conversationId).build();

        // === 第一步：触发中断 ===
        String query = "调用一下slow_tool工具";
        System.out.println("Q: " + query);
        System.out.print("A (首轮): ");

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(() -> agent.interrupt(conversationId, "首次中断"), 6000, TimeUnit.MILLISECONDS);

        TestConfig.collectStreamEvents(agent.streamForResult(query, params));
        scheduler.shutdown();

        System.out.println("\n\n中断后 hasInterruptedState = " + agent.hasInterruptedState(conversationId));

        // === 第二步：恢复 ===
        System.out.println("--- 用户点击'继续'，恢复执行 ---");
        System.out.print("A (恢复): ");

        // 不允许同 convId 有 running task，先校验
        if (agent.hasRunningTask(conversationId)) {
            System.out.println("⚠️ 同会话还有 running task，恢复被阻塞");
            return;
        }

        PauseState state = TestConfig.collectStreamEvents(agent.resumeStream(conversationId));
        System.out.println();
        if (state == null) {
            System.out.println("✅ 恢复后正常完成");
        } else {
            System.out.println("⚠️ 恢复后再次暂停（reason=" + state.getReason() + "）");
        }

        System.out.println("\n恢复后 hasInterruptedState = "
                + agent.hasInterruptedState(conversationId) + "（应为 false）");
    }

    /**
     * 测试 4：HITL 与 Interrupt 共存。
     *
     * <p>同一会话先经历 HITL 暂停（PauseAdvisor 拦截 ask_user），用户回答后恢复；
     * 若 LLM 再次追问则循环回答。首次恢复后定时触发 USER_INTERRUPT，验证两种暂停
     * 机制能在同一会话共存。
     *
     * <p>流程参考 {@link HumanInTheLoopTest#testStreamPauseResume}，主要差异：
     * 中途通过 scheduler 触发 USER_INTERRUPT，循环内按 reason 分支处理。
     */
    public static void test4_HitlAndInterruptCoexist() {
        TestConfig.printTestHeader("测试 4：HITL 与 Interrupt 共存");

        ChatModel chatModel = TestConfig.createDeepSeekV4ChatModel();
        InMemoryPauseStateStore stateStore = new InMemoryPauseStateStore();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .askUser(true)
                .stateStore(stateStore)
                .maxRounds(10)
                .build();

        String conversationId = "conv-test4-" + UUID.randomUUID();
        RunnableParams params = RunnableParams.builder().conversationId(conversationId).build();

        String query = "帮我写一封简短的问候邮件给老朋友";
        System.out.println("Q: " + query);
        System.out.print("A: ");

        // 首次流式
        PauseState pauseState = TestConfig.collectStreamEvents(agent.streamForResult(query, params));
        System.out.println();

        // 首次恢复后 2s 触发 USER_INTERRUPT（验证与 HITL 共存）
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        // 循环处理暂停（参考 HumanInTheLoopTest.testStreamPauseResume）
        int pauseRound = 0;
        while (pauseState != null) {
            pauseRound++;
            System.out.println("\n--- Agent 暂停（第 " + pauseRound + " 次）---");
            System.out.println("reason  = " + pauseState.getReason());
            printPendingToolCalls(pauseState);

            // 被用户主动中断 → 验证通过，结束
            if (pauseState.getReason() == PauseReason.USER_INTERRUPT) {
                System.out.println("\n✅ HITL 恢复后被 USER_INTERRUPT 打断，两套机制共存验证通过");
                System.out.println("phase   = " + pauseState.getSafePoint());
                System.out.println("round   = " + pauseState.getCurrentRound());
                scheduler.shutdown();
                return;
            }

            // HITL_TOOL_REQUEST → 回答 + 恢复
            Map<String, String> answers = new LinkedHashMap<>();
            for (PendingToolCall ptc : pauseState.getPendingToolCalls()) {
                String answer = mockHitlAnswer(pauseRound);
                System.out.println("用户回答: " + answer);
                answers.put(ptc.id(), answer);
            }

            // 首次恢复后定时触发 USER_INTERRUPT
            if (pauseRound == 1) {
                scheduler.schedule(() -> {
                    System.out.println("\n[2s 后] 触发 USER_INTERRUPT...");
                    boolean ok = agent.interrupt(conversationId, "用户主动中断");
                    System.out.println("[interrupt 返回] " + ok);
                }, 2000, TimeUnit.MILLISECONDS);
            }

            System.out.println("\n--- 恢复 Agent 执行 ---");
            System.out.print("A: ");
            pauseState = TestConfig.collectStreamEvents(agent.resumeStream(pauseState, answers));
            System.out.println();
        }

        scheduler.shutdown();
        System.out.println("（共暂停 " + pauseRound + " 次，Agent 完成）");
    }

    /**
     * 测试 5：放弃中断状态。
     *
     * <p>触发中断后调用 discardInterruptedState，验证 stateStore 已清空。
     */
    public static void test5_DiscardInterruptedState() {
        TestConfig.printTestHeader("测试 5：放弃中断状态");

        ChatModel chatModel = TestConfig.createDeepSeekV4ChatModel();
        InMemoryPauseStateStore stateStore = new InMemoryPauseStateStore();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .stateStore(stateStore)
                .maxRounds(5)
                .build();

        String conversationId = "conv-test5-" + UUID.randomUUID();
        RunnableParams params = RunnableParams.builder().conversationId(conversationId).build();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(() -> agent.interrupt(conversationId, "准备放弃"), 10000, TimeUnit.MILLISECONDS);

        TestConfig.collectStreamEvents(agent.streamForResult("用 500 字介绍 Java", params));
        scheduler.shutdown();

        System.out.println("中断后 hasInterruptedState = " + agent.hasInterruptedState(conversationId));

        boolean deleted = agent.discardInterruptedState(conversationId);
        System.out.println("discardInterruptedState 返回 = " + deleted);
        System.out.println("放弃后 hasInterruptedState = " + agent.hasInterruptedState(conversationId)
                + "（应为 false）");
    }

    /**
     * 测试 6：getInterruptedState 元数据检查。
     */
    public static void test6_GetInterruptedStateMetadata() {
        TestConfig.printTestHeader("测试 6：getInterruptedState 元数据");

        ChatModel chatModel = TestConfig.createDeepSeekV4ChatModel();
        InMemoryPauseStateStore stateStore = new InMemoryPauseStateStore();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .stateStore(stateStore)
                .maxRounds(5)
                .build();

        String conversationId = "conv-test6-" + UUID.randomUUID();
        RunnableParams params = RunnableParams.builder().conversationId(conversationId).build();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(() -> agent.interrupt(conversationId, "元数据检查"), 5000, TimeUnit.MILLISECONDS);

        TestConfig.collectStreamEvents(agent.streamForResult("用 500 字介绍 Python", params));
        scheduler.shutdown();

        PauseState state = agent.getInterruptedState(conversationId);
        if (state == null) {
            System.out.println("❌ getInterruptedState 返回 null");
            return;
        }
        System.out.println("conversationId   = " + state.getParams().getConversationId());
        System.out.println("reason           = " + state.getReason());
        System.out.println("phase            = " + state.getSafePoint());
        System.out.println("interruptMessage = " + state.getInterruptMessage());
        System.out.println("interruptedAt    = " + new java.util.Date(state.getInterruptedAt()));
        System.out.println("currentRound     = " + state.getCurrentRound());
        System.out.println("query            = " + truncate(state.getQuery(), 60));
        System.out.println("messages 数量    = " + (state.getMessages() == null ? 0 : state.getMessages().size()));
    }

    /**
     * 测试 7：非流式路径 Interrupt + Resume。
     *
     * <p>流程：callForResult → interrupt（scheduler 定时触发）→ Paused → resume
     *
     * <p>与流式路径（测试 1-3）的关键区别：结果通过 {@link AgentResult} 而非 Flux 获取，
     * Interrupt 和 HITL 恢复使用同一个 {@code agent.resume(PauseState, Map)} 方法。
     */
    public static void test7_CallInterruptAndResume() {
        TestConfig.printTestHeader("测试 7：非流式 Interrupt + Resume");

        ChatModel chatModel = TestConfig.createDeepSeekV4ChatModel();
        JdbcPauseStateStore stateStore = new JdbcPauseStateStore(TestConfig.createMySqlDataSource());
        SlowTool slowTool = new SlowTool();
        var toolCallbacks = ToolCallbacks.from(slowTool);

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(toolCallbacks)
                .stateStore(stateStore)
                .maxRounds(5)
                .build();

        String conversationId = "conv-test7-" + UUID.randomUUID();
        RunnableParams params = RunnableParams.builder().conversationId(conversationId).build();

        String query = "请调用 slow_task 工具，任务描述：测试中断恢复";
        System.out.println("Q: " + query);

        // 5s 后触发 interrupt（slow_task 需要 5s，所以一定在工具执行中中断）
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(() -> {
            System.out.println("\n[5s 后] 触发 interrupt...");
            boolean ok = agent.interrupt(conversationId, "非流式中断测试");
            System.out.println("[interrupt 返回] " + ok);
        }, 5000, TimeUnit.MILLISECONDS);

        AgentResult result = agent.callForResult(query, params);
        scheduler.shutdown();

        if (!(result instanceof AgentResult.Paused p)) {
            System.out.println("❌ 期望 AgentResult.Paused，实际: " + result.getClass().getSimpleName());
            return;
        }

        PauseState pauseState = p.state();
        System.out.println("\n--- 中断结果分析 ---");
        System.out.println("reason          = " + pauseState.getReason()
                + (pauseState.getReason() == PauseReason.USER_INTERRUPT ? " ✅" : " ❌"));
        System.out.println("safePoint  = " + pauseState.getSafePoint());
        System.out.println("currentRound    = " + pauseState.getCurrentRound());
        System.out.println("interruptMessage= " + pauseState.getInterruptMessage());
        printPendingToolCalls(pauseState);

        // 验证 stateStore 已保存
        System.out.println("\nstateStore hasState = " + agent.hasInterruptedState(conversationId)
                + (agent.hasInterruptedState(conversationId) ? " ✅" : " ❌"));

//        // === 恢复 ===
//        System.out.println("\n--- 恢复执行 ---");
//        PauseState pauseState = agent.getInterruptedState("conv-test7-415840d7-8a1b-4f50-aeba-1118f4e4c9be");
//        AgentResult result = agent.resume(pauseState, Map.of());
//
//        if (result instanceof AgentResult.Completed c) {
//            System.out.println("A: " + c.answer());
//            System.out.println("✅ 恢复后正常完成");
//        } else if (result instanceof AgentResult.Paused p2) {
//            System.out.println("⚠️ 恢复后再次暂停（reason=" + p2.state().getReason() + "）");
//        } else {
//            System.out.println("❌ 非预期结果: " + result.getClass().getSimpleName());
//        }
    }

    /**
     * 测试 8：非流式路径 HITL Pause + Resume。
     *
     * <p>流程：callForResult → Paused(HITL_TOOL_REQUEST) → 用户回答 → resume → 循环直到完成。
     *
     * <p>与 {@link HumanInTheLoopTest#testCallPauseResume()} 模式相同，
     * 验证 HITL 也使用同一个 {@code agent.resume(PauseState, Map)} 恢复方法。
     */
    public static void test8_CallHitlPauseResume() {
        TestConfig.printTestHeader("测试 8：非流式 HITL Pause + Resume");

        ChatModel chatModel = TestConfig.createDeepSeekV4ChatModel();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(AskUserTool.create())
                .advisors(new PauseAdvisor("ask_user"))
                .maxRounds(10)
                .build();

        String query = "帮我推荐一份生日礼物";
        System.out.println("Q: " + query);

        AgentResult result = agent.callForResult(query, RunnableParams.empty());

        int pauseRound = 0;
        while (result instanceof AgentResult.Paused p) {
            pauseRound++;
            System.out.println("\n--- Agent 暂停（第 " + pauseRound + " 次）---");
            printPendingToolCalls(p.state());

            // 校验 pause reason
            boolean isHitl = p.state().getReason() == PauseReason.HITL_TOOL_REQUEST;
            System.out.println("reason = " + p.state().getReason()
                    + (isHitl ? " ✅" : " ❌（期望 HITL_TOOL_REQUEST）"));

            Map<String, String> answers = new LinkedHashMap<>();
            for (PendingToolCall ptc : p.state().getPendingToolCalls()) {
                String mockAnswer = mockHitlAnswer(pauseRound);
                System.out.println("用户回答: " + mockAnswer);
                answers.put(ptc.id(), mockAnswer);
            }

            System.out.println("\n--- 恢复 Agent 执行 ---");
            result = agent.resume(p.state(), answers);
        }

        if (result instanceof AgentResult.Completed c) {
            System.out.println("A: " + c.answer());
            System.out.println("\n✅ 完成（共暂停 " + pauseRound + " 次）");
        } else if (result instanceof AgentResult.Failed f) {
            System.out.println("❌ 失败: " + f.error());
        }
    }

    // ==================== 测试工具 ====================

    /**
     * 慢速工具 — 模拟 5 秒耗时操作。
     */
    static class SlowTool {
        @Tool(name = "slow_task", description = "模拟一个耗时 5 秒的耗时任务")
        public String slowTask(@ToolParam(description = "任务描述") String task) {
            System.out.println("\n[SlowTool] 开始执行，预计 5 秒: " + task);
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "interrupted: " + e.getMessage();
            }
            System.out.println("[SlowTool] 完成");
            return "完成: " + task;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "(null)";
        return s.length() <= max ? s : s.substring(0, max) + "...(" + s.length() + " chars)";
    }

    /** 打印 HITL 待确认工具调用（与 HumanInTheLoopTest 风格一致）。 */
    private static void printPendingToolCalls(PauseState state) {
        for (PendingToolCall ptc : state.getPendingToolCalls()) {
            System.out.println("  [" + ptc.name() + "] id=" + ptc.id());
            System.out.println("    " + ptc.arguments());
        }
    }

    /** 按暂停轮次模拟不同的 HITL 答案。 */
    private static String mockHitlAnswer(int round) {
        return switch (round) {
            case 1 -> "老朋友叫张三；3年没联系；轻松叙旧";
            case 2 -> "可以提到大学一起打球；邮件主题：周末聚会";
            default -> "没别的了，直接写吧";
        };
    }

    // ==================== Main ====================

    public static void main(String[] args) {
        System.out.println("===============================================");
        System.out.println("       Interrupt & Resume Test");
        System.out.println("===============================================");
        System.out.println("ChatModel: " + TestConfig.CHAT_MODEL);
        System.out.println("===============================================\n");

        int testNumber = 7;

        switch (testNumber) {
            case 1 -> test1_InterruptDuringLlmStreaming();
            case 2 -> test2_InterruptDuringToolExecution();
            case 3 -> test3_ResumeAfterInterrupt();
            case 4 -> test4_HitlAndInterruptCoexist();
            case 5 -> test5_DiscardInterruptedState();
            case 6 -> test6_GetInterruptedStateMetadata();
            case 7 -> test7_CallInterruptAndResume();
            case 8 -> test8_CallHitlPauseResume();
            default -> System.out.println("无效的测试编号: " + testNumber);
        }

        System.out.println("\n===============================================");
        System.out.println("       Test Completed");
        System.out.println("===============================================");
    }
}
