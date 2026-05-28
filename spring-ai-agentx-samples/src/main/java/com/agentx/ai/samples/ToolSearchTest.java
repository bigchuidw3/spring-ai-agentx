package com.agentx.ai.samples;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.model.AgentStreamEvent;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.tools.toolsearch.ToolSearchConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * ToolSearch 延迟工具搜索测试。
 *
 * <p>验证 deferredTools 按需发现能力：alwaysLoad 工具始终可见，
 * deferred 工具通过 tool_search 元工具按需搜索加载。
 *
 * @author bigchui
 * 
 */
public class ToolSearchTest {

    // ===== 模拟 alwaysLoad 工具 =====

    static class CoreTools {
        @Tool(description = "获取当前日期和时间")
        public String getTime() {
            return java.time.LocalDateTime.now().toString();
        }
    }

    // ===== 模拟 deferred 工具 =====

    static class WeatherTools {
        @Tool(description = "查询指定城市的天气预报，包括温度、湿度、风力等信息")
        public String getWeather(@ToolParam(description = "城市名称") String city) {
            return city + "：晴，25°C，湿度 60%，微风";
        }
    }

    static class SlackTools {
        @Tool(description = "向Slack指定频道发送消息，支持Markdown格式")
        public String sendSlackMessage(
                @ToolParam(description = "频道名称") String channel,
                @ToolParam(description = "消息内容") String message) {
            return "已发送到 #" + channel + "：" + message;
        }
    }

    // ===== 测试方法 =====

    /**
     * 测试 1：同步调用 — LLM 通过 tool_search 发现并使用 deferred 工具。
     */
    public static void testCallWithToolSearch() {
        TestConfig.printTestHeader("测试 1：同步 — tool_search 发现 deferred 工具");

        ChatModel chatModel = TestConfig.createChatModel();
        ToolCallback[] alwaysLoadTools = ToolCallbacks.from(new CoreTools());
        ToolCallback[] deferredTools = ReactAgent.mergeTools(
                ToolCallbacks.from(new WeatherTools()),
                ToolCallbacks.from(new SlackTools())
        );

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(alwaysLoadTools)
                .deferredTools(ToolSearchConfig.defaults(), deferredTools)
                .maxRounds(10)
                .build();

        // 这个请求需要 weather 工具，但 weather 不在 alwaysLoad 中
        // LLM 应该先调用 tool_search 搜索，然后下一轮调用 getWeather
        String query = "帮我查一下北京的天气，然后把结果发到 #general 频道";
        System.out.println("Q: " + query);
        String answer = agent.call(query);
        System.out.println("A: " + answer);
    }

    /**
     * 测试 2：流式调用 — 验证 tool_search 在流式模式下的完整事件流。
     */
    public static void testStreamWithToolSearch() {
        TestConfig.printTestHeader("测试 2：流式 — tool_search 事件流");

        ChatModel chatModel = TestConfig.createChatModel();
        ToolCallback[] alwaysLoadTools = ToolCallbacks.from(new CoreTools());
        ToolCallback[] deferredTools = ReactAgent.mergeTools(
                ToolCallbacks.from(new WeatherTools()),
                ToolCallbacks.from(new SlackTools())
        );

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(alwaysLoadTools)
//                .tools(deferredTools)
                .deferredTools(ToolSearchConfig.defaults(), deferredTools)
                .maxRounds(10)
                .build();

        String query = "先查询上海的天气，拿到结果后，再把把天气结果发到 #outline 频道";
        System.out.println("Q: " + query);
        System.out.println("--- Events Start ---");

        agent.streamForResult(query, RunnableParams.empty())
                .doOnNext(TestConfig::printEvent)
                .doOnError(err -> System.err.println("Error: " + err.getMessage()))
                .blockLast();

        System.out.println("--- Events End ---");
    }

    /**
     * 测试 3：验证 Session 隔离 — 两次请求的 discoveredNames 不互相泄漏。
     * <p>
     * 请求 1 搜索并使用了 weather + slack 工具。
     * 请求 2 如果隔离失效，getWeather 会直接可用（无需 tool_search）；
     * 如果隔离正常，请求 2 应重新走 tool_search 发现流程。
     */
    public static void testSessionIsolation() {
        TestConfig.printTestHeader("测试 3：Session 隔离 — discoveredNames 不跨请求泄漏");

        ChatModel chatModel = TestConfig.createChatModel();
        ToolCallback[] alwaysLoadTools = ToolCallbacks.from(new CoreTools());
        ToolCallback[] deferredTools = ReactAgent.mergeTools(
                ToolCallbacks.from(new WeatherTools()),
                ToolCallbacks.from(new SlackTools())
        );

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(alwaysLoadTools)
                .deferredTools(ToolSearchConfig.defaults(), deferredTools)
                .maxRounds(10)
                .build();

        // === 请求 1：触发 tool_search，发现 weather + slack ===
        String query1 = "帮我查一下北京的天气，然后把结果发到 #general 频道";
        System.out.println("=== 请求 1 ===");
        System.out.println("Q: " + query1);
        System.out.println("--- Events Start ---");
        agent.streamForResult(query1, RunnableParams.empty())
                .doOnNext(TestConfig::printEvent)
                .doOnError(err -> System.err.println("Error: " + err.getMessage()))
                .blockLast();
        System.out.println("--- Events End ---\n");

        // === 请求 2：同样需要 weather 工具，应重新走 tool_search ===
        // 如果 discoveredNames 泄漏，LLM 会直接调用 getWeather 而不再搜索
        String query2 = "帮我查一下上海的天气";
        System.out.println("=== 请求 2 ===");
        System.out.println("Q: " + query2);
        System.out.println("--- Events Start ---");
        agent.streamForResult(query2, RunnableParams.empty())
                .doOnNext(TestConfig::printEvent)
                .doOnError(err -> System.err.println("Error: " + err.getMessage()))
                .blockLast();
        System.out.println("--- Events End ---\n");

        System.out.println(">>> 验证：如果请求 2 仍然出现 ToolStart(tool_search) 事件，说明 Session 隔离正常");
        System.out.println(">>> 如果请求 2 直接调用 getWeather 而没有 tool_search，说明 discoveredNames 泄漏了");
    }

    // ===== Main =====

    public static void main(String[] args) {
        System.out.println("===============================================");
        System.out.println("       ToolSearch Test");
        System.out.println("===============================================");
        System.out.println("ChatModel:   " + TestConfig.CHAT_MODEL);
        System.out.println("===============================================");

        int testNumber = 3;

        switch (testNumber) {
            case 1 -> testCallWithToolSearch();
            case 2 -> testStreamWithToolSearch();
            case 3 -> testSessionIsolation();
            default -> System.out.println("无效的测试编号: " + testNumber);
        }

        System.out.println("\n===============================================");
        System.out.println("       Test Completed");
        System.out.println("===============================================");
    }
}
