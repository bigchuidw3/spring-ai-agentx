package com.agentx.ai.samples.v1_2;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.interrupt.JdbcPauseStateStore;
import com.agentx.ai.core.model.PauseState;
import com.agentx.ai.core.model.PendingToolCall;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.model.ThinkingMode;
import com.agentx.ai.samples.TestConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 多子 Agent 并发中断与续跑测试（流式）。
 *
 * <p>主 Agent 并行派发研究员、分析师两个子 Agent，各自调用 slow_task（20s）调研；
 * 15s 后用户中断，验证两个子 Agent 的快照都作为 children 进入 master 快照，
 * 恢复时两个子 Agent 都从各自快照续跑（不重跑）。
 *
 * @author bigchui
 */
public class SubAgentInterruptResumeTest {

    static ChatModel chatModel;

    public static void init() {
        chatModel = TestConfig.createDeepSeekV4ChatModel();
    }

    public static void testSubAgentInterruptAndResume() {
        TestConfig.printTestHeader("子 Agent 中断与续跑（流式）");

        DataSource dataSource = TestConfig.createMySqlDataSource();
        JdbcPauseStateStore stateStore = new JdbcPauseStateStore(dataSource);
        SlowTool slowTool = new SlowTool();
        var toolCallbacks = ToolCallbacks.from(slowTool);

        ReactAgent pm = ReactAgent.builder()
                .chatModel(chatModel)
                .thinkingMode(ThinkingMode.REASONING_CONTENT)
                .dataSource(dataSource)
                .stateStore(stateStore)
                .instructions("你是项目经理。收到调研任务必须同时并行调用 call_researcher 和 call_analyst 委派给研究员和分析师，不要自己分析。")
                .subAgent(() -> ReactAgent.builder()
                        .name("researcher")
                        .description("研究员，负责深入调研技术现状")
                        .chatModel(chatModel)
                        .thinkingMode(ThinkingMode.REASONING_CONTENT)
                        .instructions("你是研究员。收到任务后先调用 slow_task 工具做深入调研（耗时约 20 秒），拿到结果后总结输出结论。")
                        .tools(toolCallbacks)
                        .maxRounds(10)
                        .build())
                .subAgent(() -> ReactAgent.builder()
                        .name("analyst")
                        .description("分析师，负责分析市场趋势")
                        .chatModel(chatModel)
                        .thinkingMode(ThinkingMode.REASONING_CONTENT)
                        .instructions("你是分析师。收到任务后先调用 slow_task 工具做深入分析（耗时约 20 秒），拿到结果后总结输出结论。")
                        .tools(toolCallbacks)
                        .maxRounds(10)
                        .build())
                .maxRounds(20)
                .build();

        String convId = "conv_dff1d3cf-78a0-46ef-88f3-ec6a99c0986a";
        String userId = TestConfig.randomUserId("user_subir");
        RunnableParams params = TestConfig.buildParams(convId, userId);
        System.out.println("convId = " + convId);

        String query = "请并行委派研究员和分析师，分别调研：企业级多 Agent 框架的技术现状，以及市场发展趋势。";
        System.out.println("Q: " + query + "\n");

        // === 第一步：流式执行，15 秒后触发中断 ===
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(() -> {
            System.out.println("\n[15s 后] 触发 interrupt...");
            boolean ok = pm.interrupt(convId, "用户主动中断");
            System.out.println("[interrupt 返回] " + ok);
        }, 15000, TimeUnit.MILLISECONDS);

        PauseState state = TestConfig.collectStreamEvents(pm.streamForResult(query, params));
        scheduler.shutdown();

        // === 打印中断快照 ===
        if (state == null) {
            System.out.println("❌ 未捕获 Paused 事件（子 Agent 可能在 15s 内已完成，请调大 slow_task 耗时）");
            return;
        }
        printSnapshot(state, "master");

        System.out.println("\nhasInterruptedState = " + pm.hasInterruptedState(convId));

        // === 第二步：恢复（纯续跑，两个子 Agent 都从快照续跑不重跑）===
        System.out.println("\n--- 用户点击'继续'，恢复执行 ---");
        System.out.println("resumeStream 前 hasRunningTask = " + pm.hasRunningTask(convId));
        PauseState resumed = TestConfig.collectStreamEvents(pm.resumeStream(convId));
        if (resumed == null) {
            System.out.println("\n✅ 恢复后正常完成");
        } else {
            System.out.println("\n⚠️ 恢复后再次暂停（reason=" + resumed.getReason() + "）");
            printSnapshot(resumed, "resumed");
        }
    }

    private static void printSnapshot(PauseState state, String label) {
        System.out.println("\n--- [" + label + "] 中断快照分析 ---");
        System.out.println("reason          = " + state.getReason());
        System.out.println("safePoint       = " + state.getSafePoint());
        System.out.println("currentRound    = " + state.getCurrentRound());
        System.out.println("agentKey        = " + state.getAgentKey());
        System.out.println("pendingTools    = " + state.getPendingToolCalls().size());
        for (PendingToolCall ptc : state.getPendingToolCalls()) {
            System.out.println("  [" + ptc.name() + "] id=" + ptc.id());
        }
        List<PauseState> children = state.getChildren();
        System.out.println("children        = " + (children == null ? 0 : children.size()));
        if (children != null) {
            for (PauseState child : children) {
                System.out.println("  - child.agentKey        = " + child.getAgentKey());
                System.out.println("    child.parentToolCallId= " + child.getParentToolCallId());
                System.out.println("    child.safePoint       = " + child.getSafePoint());
                System.out.println("    child.currentRound    = " + child.getCurrentRound());
                System.out.println("    child.pendingTools    = " + child.getPendingToolCalls().size());
                for (PendingToolCall ptc : child.getPendingToolCalls()) {
                    System.out.println("      [" + ptc.name() + "] id=" + ptc.id());
                }
            }
        }
    }

    static class SlowTool {
        @Tool(name = "slow_task", description = "模拟耗时 20 秒的深入调研任务")
        public String slowTask(@ToolParam(description = "调研主题") String topic) {
            System.out.println("\n[SlowTool] 开始调研，预计 20 秒: " + topic);
            try {
                Thread.sleep(20000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "interrupted: " + e.getMessage();
            }
            System.out.println("[SlowTool] 调研完成");
            return "调研完成: " + topic;
        }
    }

    public static void main(String[] args) {
        System.out.println("===============================================");
        System.out.println(" SubAgent Interrupt & Resume Test (v1_2)");
        System.out.println("===============================================");

        init();
        testSubAgentInterruptAndResume();

        System.out.println("\n===============================================");
        System.out.println("       Test Completed");
        System.out.println("===============================================");
    }
}
