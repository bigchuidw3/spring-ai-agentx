package com.agentx.ai.samples;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.model.*;
import org.springframework.ai.chat.model.ChatModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ThinkingMode 思考模型适配测试。
 *
 * <p>测试内容：
 * <ul>
 *   <li>测试 1：MiniMax THINK_TAG — 流式</li>
 *   <li>测试 2：MiniMax THINK_TAG — 非流式</li>
 *   <li>测试 3：GLM REASONING_CONTENT — 流式</li>
 *   <li>测试 4：GLM REASONING_CONTENT — 非流式</li>
 * </ul>
 *
 * @author bigchui
 */
public class ThinkingModeTest {

    /**
     * 测试 1：MiniMax THINK_TAG — 流式。
     *
     * <p>MiniMax 模型通过 {@code <think/>} 标签输出思考内容，
     * 验证 ThinkTagParser 正确拆分为 Thinking / Text 事件。
     */
    public static void testMiniMaxStream() {
        TestConfig.printTestHeader("测试 1：MiniMax THINK_TAG — 流式");

        ChatModel chatModel = TestConfig.createMiniMaxChatModel();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .thinkingMode(ThinkingMode.THINK_TAG)
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

        printStats(events);
    }

    /**
     * 测试 2：MiniMax THINK_TAG — 非流式。
     *
     * <p>非流式调用，验证 callForResult 返回的 answer 不含 think 标签内容，
     * think 内容正确存储到 session。
     */
    public static void testMiniMaxCall() {
        TestConfig.printTestHeader("测试 2：MiniMax THINK_TAG — 非流式");

        ChatModel chatModel = TestConfig.createMiniMaxChatModel();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .thinkingMode(ThinkingMode.THINK_TAG)
                .maxRounds(3)
                .build();

        String query = "简单介绍下 Java 的特点";
        System.out.println("Q: " + query);

        AgentResult result = agent.callForResult(query, RunnableParams.empty());

        if (result instanceof AgentResult.Completed c) {
            System.out.println("Answer: " + c.answer());
            String think = c.think();
            if (think != null && !think.isEmpty()) {
                System.out.println("Think: " + think);
            }
            Map<String, Object> stageOutputs = c.stageOutputs();
            if (!stageOutputs.isEmpty()) {
                System.out.println("StageOutputs: " + stageOutputs);
            }
        } else if (result instanceof AgentResult.Paused p) {
            System.out.println("Agent 暂停（此场景不应暂停）");
        }
    }

    /**
     * 测试 3：GLM REASONING_CONTENT — 流式。
     *
     * <p>GLM 模型通过 {@code reasoning_content} 字段输出思考内容，
     * 验证框架正确从 metadata 提取并分发为 Thinking 事件。
     */
    public static void testGLMStream() {
        TestConfig.printTestHeader("测试 3：GLM REASONING_CONTENT — 流式");

        ChatModel chatModel = TestConfig.createChatModel();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .thinkingMode(ThinkingMode.REASONING_CONTENT)
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

        printStats(events);
    }

    /**
     * 测试 4：GLM REASONING_CONTENT — 非流式。
     *
     * <p>非流式调用，验证 callForResult 返回的 answer 为纯正文（不含思考内容），
     * reasoning_content 正确存储到 session 的 think 列。
     */
    public static void testGLMCall() {
        TestConfig.printTestHeader("测试 4：GLM REASONING_CONTENT — 非流式");

        ChatModel chatModel = TestConfig.createChatModel();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .thinkingMode(ThinkingMode.REASONING_CONTENT)
                .maxRounds(3)
                .build();

        String query = "你是谁？";
        System.out.println("Q: " + query);

        AgentResult result = agent.callForResult(query, RunnableParams.empty());

        if (result instanceof AgentResult.Completed c) {
            System.out.println("Answer: " + c.answer());
            String think = c.think();
            if (think != null && !think.isEmpty()) {
                System.out.println("Think: " + think);
            }
            Map<String, Object> stageOutputs = c.stageOutputs();
            if (!stageOutputs.isEmpty()) {
                System.out.println("StageOutputs: " + stageOutputs);
            }
        } else if (result instanceof AgentResult.Paused p) {
            System.out.println("Agent 暂停（此场景不应暂停）");
        }
    }

    // ===== Helper =====

    private static void printStats(List<AgentStreamEvent> events) {
        long thinkingCount = events.stream().filter(e -> e instanceof AgentStreamEvent.Thinking).count();
        long textCount = events.stream().filter(e -> e instanceof AgentStreamEvent.Text).count();
        long stageOutputCount = events.stream().filter(e -> e instanceof AgentStreamEvent.StageOutput).count();
        System.out.println("\nThinking events: " + thinkingCount);
        System.out.println("Text events: " + textCount);
        System.out.println("StageOutput events: " + stageOutputCount);
    }

    // ===== Main =====

    public static void main(String[] args) {
        System.out.println("===============================================");
        System.out.println("       ThinkingMode Test");
        System.out.println("===============================================");

        int testNumber = 4;

        switch (testNumber) {
            case 1 -> testMiniMaxStream();
            case 2 -> testMiniMaxCall();
            case 3 -> testGLMStream();
            case 4 -> testGLMCall();
            default -> System.out.println("无效的测试编号: " + testNumber);
        }

        System.out.println("\n===============================================");
        System.out.println("       Test Completed");
        System.out.println("===============================================");
    }
}
