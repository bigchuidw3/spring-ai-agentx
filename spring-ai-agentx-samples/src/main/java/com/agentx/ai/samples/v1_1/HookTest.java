package com.agentx.ai.samples.v1_1;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.hook.AfterCallEvent;
import com.agentx.ai.core.hook.AfterReasoningEvent;
import com.agentx.ai.core.hook.AfterToolExecutionEvent;
import com.agentx.ai.core.hook.AgentHook;
import com.agentx.ai.core.hook.BeforeCallEvent;
import com.agentx.ai.core.hook.BeforeReasoningEvent;
import com.agentx.ai.core.hook.BeforeToolExecutionEvent;
import com.agentx.ai.core.hook.ErrorEvent;
import com.agentx.ai.core.model.AgentStreamEvent;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.model.ThinkingMode;
import com.agentx.ai.samples.TestConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import javax.sql.DataSource;

/**
 * Hook 生命周期测试 — 流式接口。
 *
 * <p>每个方法演示一个 Hook 事件，单独注册、单独运行：
 * <ul>
 *   <li>1 BeforeCall       — 调用开始（读 query / messages）</li>
 *   <li>2 BeforeReasoning  — 每轮推理前（读 round / 消息数）</li>
 *   <li>3 AfterReasoning   — 每轮推理后（读 token 统计 / 工具决策）</li>
 *   <li>4 BeforeToolExec   — 工具执行前（可改 arguments）</li>
 *   <li>5 AfterToolExec    — 工具执行后（读结果 / 耗时）</li>
 *   <li>6 AfterCall        — 调用结束（读总耗时 / 持久化后）</li>
 *   <li>7 Error            — 异常捕获（读 phase / willRetry）</li>
 * </ul>
 *
 * @author bigchui
 */
public class HookTest {

    private static final String QUERY = "帮我查一下北京现在的天气，并用一句话给出穿衣建议";

    private static ReactAgent buildAgent(AgentHook hook) {
        ChatModel chatModel = TestConfig.createDeepSeekV4ChatModel();
        DataSource dataSource = TestConfig.createMySqlDataSource();
        ToolCallback[] tools = ToolCallbacks.from(new SessionTools());
        return ReactAgent.builder()
                .chatModel(chatModel)
                .dataSource(dataSource)
                .thinkingMode(ThinkingMode.REASONING_CONTENT)
                .tools(tools)
                .hooks(hook)
                .maxRounds(10)
                .build();
    }

    private static void runStream(ReactAgent agent) {
        RunnableParams params = TestConfig.buildParams(
                TestConfig.randomConvId(), TestConfig.randomUserId("hook"));
        System.out.println("Q: " + QUERY);
        agent.streamForResult(QUERY, params)
                .doOnNext(TestConfig::printEvent)
                .doOnError(e -> System.err.println("Stream Error: " + e.getMessage()))
                .blockLast();
    }

    // ===== 1. BeforeCall =====

    public static void testBeforeCallHook() {
        TestConfig.printTestHeader("Hook 1：BeforeCall — 调用开始");
        AgentHook hook = event -> {
            if (event instanceof BeforeCallEvent e) {
                var ctx = e.getRuntimeContext();
                System.out.println("[BeforeCall] query=\"" + ctx.getQuery() + "\""
                        + " userId=" + ctx.getUserId()
                        + " 初始消息数=" + (ctx.getMessages() == null ? 0 : ctx.getMessages().size()));
            }
            return event;
        };
        runStream(buildAgent(hook));
    }

    // ===== 2. BeforeReasoning =====

    public static void testBeforeReasoningHook() {
        TestConfig.printTestHeader("Hook 2：BeforeReasoning — 每轮推理前");
        AgentHook hook = event -> {
            if (event instanceof BeforeReasoningEvent e) {
                System.out.println("[BeforeReasoning] round=" + e.getRound()
                        + " 消息数=" + e.getRuntimeContext().getMessages().size());
            }
            return event;
        };
        runStream(buildAgent(hook));
    }

    // ===== 3. AfterReasoning =====

    public static void testAfterReasoningHook() {
        TestConfig.printTestHeader("Hook 3：AfterReasoning — 每轮推理后");
        AgentHook hook = event -> {
            if (event instanceof AfterReasoningEvent e) {
                System.out.println("[AfterReasoning] round=" + e.getRound()
                        + " text=" + truncate(e.getText(), 40)
                        + " toolCalls=" + (e.getToolCalls() == null ? 0 : e.getToolCalls().size())
                        + " prompt=" + e.getPromptTokens()
                        + " completion=" + e.getCompletionTokens()
                        + " 耗时=" + e.getDurationMs() + "ms");
                // 通过 emitter 往流里注入每轮 token 统计
                var emitter = e.getRuntimeContext().getEmitter();
                if (emitter != null) {
                    emitter.accept(new AgentStreamEvent.Thinking("=================这是一条测试的hook消息================="));
                }
            }
            return event;
        };
        runStream(buildAgent(hook));
    }

    // ===== 4. BeforeToolExecution =====

    public static void testBeforeToolExecutionHook() {
        TestConfig.printTestHeader("Hook 4：BeforeToolExecution — 修改工具参数");
        AgentHook hook = event -> {
            if (event instanceof BeforeToolExecutionEvent e) {
                System.out.println("[BeforeToolExec] " + e.getToolName()
                        + " 原始参数=" + e.getArguments());
                // 示例：把 city 参数从"北京"改成"上海"，看天气工具返回哪个城市
                String modified = e.getArguments().replace("北京", "上海");
                if (!modified.equals(e.getArguments())) {
                    e.setArguments(modified);
                    System.out.println("[BeforeToolExec] 已篡改参数 → " + modified);
                }
            }
            return event;
        };
        runStream(buildAgent(hook));
    }

    // ===== 5. AfterToolExecution =====

    public static void testAfterToolExecutionHook() {
        TestConfig.printTestHeader("Hook 5：AfterToolExecution — 工具执行后");
        AgentHook hook = event -> {
            if (event instanceof AfterToolExecutionEvent e) {
                System.out.println("[AfterToolExec] " + e.getToolName()
                        + " success=" + e.isSuccess()
                        + " 耗时=" + e.getDurationMs() + "ms"
                        + " result=" + truncate(e.getResult(), 60));
            }
            return event;
        };
        runStream(buildAgent(hook));
    }


    // ===== 6. AfterCall =====

    public static void testAfterCallHook() {
        TestConfig.printTestHeader("Hook 6：AfterCall — 调用结束");
        AgentHook hook = event -> {
            if (event instanceof AfterCallEvent e) {
                System.out.println("[AfterCall] 总耗时=" + e.getDurationMs() + "ms"
                        + " prompt=" + e.getRuntimeContext().getTotalPromptTokens()
                        + " completion=" + e.getRuntimeContext().getTotalCompletionTokens()
                        + " rounds=" + e.getRuntimeContext().getTotalRounds());
            }
            return event;
        };
        runStream(buildAgent(hook));
    }

    // ===== 7. ErrorEvent =====

    /**
     * 用错误的 API Key 触发 LLM 调用失败，验证 ErrorEvent 机制。
     * <p>ErrorEvent 仅在 LLM 调用层面失败时触发（如 401 认证失败），
     * 工具内部异常会被框架捕获并转为错误消息，不走 ErrorEvent。
     */
    public static void testErrorHook() {
        TestConfig.printTestHeader("Hook 7：ErrorEvent — bad API key 触发 LLM 错误");
        ChatModel chatModel = TestConfig.createBadApiKeyChatModel();
        AgentHook hook = event -> {
            if (event instanceof ErrorEvent e) {
                System.out.println("[Error] phase=" + e.getPhase()
                        + " retry=" + e.getRetryAttempt()
                        + " willRetry=" + e.isWillRetry()
                        + " error=" + e.getError().getMessage());
            }
            return event;
        };
        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .hooks(hook)
                .maxRounds(2)
                .maxRetries(1)
                .build();
        runStream(agent);
    }

    // ===== Helper =====

    private static String truncate(String s, int max) {
        if (s == null) return "(null)";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ===== Main =====

    public static void main(String[] args) {
        System.out.println("===============================================");
        System.out.println("       Hook Lifecycle Test (v1_1)");
        System.out.println("===============================================");
        System.out.println("ChatModel: " + TestConfig.CHAT_MODEL);
        System.out.println("===============================================");

        int testNumber = 7;
        switch (testNumber) {
            case 1 -> testBeforeCallHook();
            case 2 -> testBeforeReasoningHook();
            case 3 -> testAfterReasoningHook();
            case 4 -> testBeforeToolExecutionHook();
            case 5 -> testAfterToolExecutionHook();
            case 6 -> testAfterCallHook();
            case 7 -> testErrorHook();
            default -> System.out.println("无效的测试编号: " + testNumber);
        }

        System.out.println("\n===============================================");
        System.out.println("       Test Completed");
        System.out.println("===============================================");
    }
}
