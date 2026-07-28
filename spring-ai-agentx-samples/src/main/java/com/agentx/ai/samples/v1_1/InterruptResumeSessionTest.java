package com.agentx.ai.samples.v1_1;

import com.agentx.ai.core.advisors.PauseAdvisor;
import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.interrupt.JdbcPauseStateStore;
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
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 中断恢复 + 会话落库测试。
 */
public class InterruptResumeSessionTest {

    static class SlowTool {
        @Tool(name = "slow_task", description = "模拟一个耗时 8 秒的耗时任务")
        public String slowTask(@ToolParam(description = "任务描述") String task) {
            System.out.println("\n[SlowTool] 开始执行: " + task);
            try {
                Thread.sleep(8000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "interrupted: " + e.getMessage();
            }
            System.out.println("[SlowTool] 完成");
            return "完成: " + task;
        }
    }

    private static void verifyPersisted(DataSource dataSource, String convId) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        System.out.println("\n--- 落库验证 convId=" + convId + " ---");

        List<Map<String, Object>> conversationRows = jdbc.queryForList(
                "SELECT session_id, status, LEFT(question, 60) AS question, created_at, completed_at "
                        + "FROM agentx_conversation WHERE conversation_id = ? ORDER BY created_at",
                convId);
        System.out.println("[agentx_conversation] 共 " + conversationRows.size() + " 行");
        conversationRows.forEach(row -> System.out.println("  " + row));

        List<Map<String, Object>> sessionRows = jdbc.queryForList(
                "SELECT state_key, COUNT(*) AS row_count, COALESCE(SUM(CHAR_LENGTH(state_data)), 0) AS total_chars "
                        + "FROM agentx_session WHERE conversation_id = ? GROUP BY state_key ORDER BY state_key",
                convId);
        System.out.println("[agentx_session state_key 分布]");
        sessionRows.forEach(row -> System.out.println("  " + row.get("state_key")
                + " : rows=" + row.get("row_count")
                + ", chars=" + row.get("total_chars")));

        List<Map<String, Object>> pauseRows = jdbc.queryForList(
                "SELECT conversation_id, reason, safe_point, current_round, interrupted_at "
                        + "FROM agentx_pause_state WHERE conversation_id = ?",
                convId);
        System.out.println("[agentx_pause_state] 共 " + pauseRows.size() + " 行");
        pauseRows.forEach(row -> System.out.println("  " + row));
    }

    private static void printPendingToolCalls(PauseState state) {
        for (PendingToolCall ptc : state.getPendingToolCalls()) {
            System.out.println("  [" + ptc.name() + "] id=" + ptc.id());
            System.out.println("    " + ptc.arguments());
        }
    }

    private static String mockHitlAnswer(int round) {
        return switch (round) {
            case 1 -> "收件人是老朋友张三，语气轻松一点";
            case 2 -> "可以提一下很久没见，想约周末吃饭";
            default -> "没别的了，直接写吧";
        };
    }

    public static void testInterruptWithSessionPersistence() {
        TestConfig.printTestHeader("中断恢复 + 会话落库测试");

        ChatModel chatModel = TestConfig.createDeepSeekV4ChatModel();
        DataSource dataSource = TestConfig.createMySqlDataSource();
        JdbcPauseStateStore stateStore = new JdbcPauseStateStore(dataSource);
        var toolCallbacks = ToolCallbacks.from(new SlowTool());

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .dataSource(dataSource)
                .stateStore(stateStore)
                .tools(toolCallbacks)
                .maxRounds(5)
                .build();

        String conversationId = "conv-interrupt-" + UUID.randomUUID();
        RunnableParams params = RunnableParams.builder().conversationId(conversationId).build();
        String query = "请调用 slow_task 工具，任务描述：测试中断恢复与会话落库";

        System.out.println("convId = " + conversationId);
        System.out.println("Q: " + query);

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(() -> {
            System.out.println("\n[10s 后] 触发 interrupt...");
            boolean ok = agent.interrupt(conversationId, "中断恢复测试");
            System.out.println("[interrupt 返回] " + ok);
        }, 10000, TimeUnit.MILLISECONDS);

        AgentResult result = agent.callForResult(query, params);
        scheduler.shutdown();

        if (!(result instanceof AgentResult.Paused p)) {
            System.out.println("❌ 期望 AgentResult.Paused，实际: " + result);
            return;
        }

        PauseState pauseState = p.state();
        System.out.println("\n--- 中断结果分析 ---");
        System.out.println("reason = " + pauseState.getReason());
        System.out.println("safePoint = " + pauseState.getSafePoint());
        System.out.println("currentRound = " + pauseState.getCurrentRound());
        printPendingToolCalls(pauseState);
        System.out.println("hasInterruptedState = " + agent.hasInterruptedState(conversationId));

        verifyPersisted(dataSource, conversationId);

        System.out.println("\n--- 恢复执行 ---");
        AgentResult resumed = agent.resume(pauseState, Map.of());
        if (resumed instanceof AgentResult.Completed c) {
            System.out.println("A: " + c.answer());
            System.out.println("✅ 恢复后正常完成");
        } else {
            System.out.println("⚠️ 恢复结果: " + resumed);
        }

        System.out.println("\n恢复后 hasInterruptedState = " + agent.hasInterruptedState(conversationId));
        verifyPersisted(dataSource, conversationId);
    }

    public static void testHitlWithSessionPersistence() {
        TestConfig.printTestHeader("HITL + 会话落库测试");

        ChatModel chatModel = TestConfig.createDeepSeekV4ChatModel();
        DataSource dataSource = TestConfig.createMySqlDataSource();
        JdbcPauseStateStore stateStore = new JdbcPauseStateStore(dataSource);

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .dataSource(dataSource)
                .stateStore(stateStore)
                .askUser(true)
                .maxRounds(10)
                .build();

        String conversationId = "conv-hitl-" + UUID.randomUUID();
        RunnableParams params = RunnableParams.builder().conversationId(conversationId).build();
        String query = "帮我写一封给老朋友的问候邮件";

        System.out.println("convId = " + conversationId);
        System.out.println("Q: " + query);

        AgentResult result = agent.callForResult(query, params);
        int pauseRound = 0;
        while (result instanceof AgentResult.Paused p) {
            pauseRound++;
            PauseState pauseState = p.state();
            System.out.println("\n--- Agent 暂停（第 " + pauseRound + " 次）---");
            System.out.println("reason = " + pauseState.getReason());
            printPendingToolCalls(pauseState);
            verifyPersisted(dataSource, conversationId);

            Map<String, String> answers = new LinkedHashMap<>();
            for (PendingToolCall ptc : pauseState.getPendingToolCalls()) {
                String answer = mockHitlAnswer(pauseRound);
                System.out.println("用户回答: " + answer);
                answers.put(ptc.id(), answer);
            }

            System.out.println("\n--- 恢复 Agent 执行 ---");
            result = agent.resume(pauseState, answers);
        }

        if (result instanceof AgentResult.Completed c) {
            System.out.println("A: " + c.answer());
            System.out.println("✅ 完成（共暂停 " + pauseRound + " 次）");
        } else {
            System.out.println("⚠️ 最终结果: " + result);
        }

        verifyPersisted(dataSource, conversationId);
    }

    public static void main(String[] args) {
        System.out.println("===============================================");
        System.out.println(" Interrupt Resume + Session Test (v1_1)");
        System.out.println("===============================================");
        System.out.println("ChatModel: " + TestConfig.CHAT_MODEL);
        System.out.println("===============================================");

        int testNumber = 1;
        switch (testNumber) {
            case 1 -> testInterruptWithSessionPersistence();
            case 2 -> testHitlWithSessionPersistence();
            case 0 -> {
                testInterruptWithSessionPersistence();
                testHitlWithSessionPersistence();
            }
            default -> System.out.println("无效的测试编号: " + testNumber);
        }

        System.out.println("\n===============================================");
        System.out.println("       Test Completed");
        System.out.println("===============================================");
    }
}
