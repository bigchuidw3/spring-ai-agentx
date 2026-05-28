package com.agentx.ai.samples;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.model.AgentResult;
import com.agentx.ai.core.model.AgentStreamEvent;
import com.agentx.ai.core.model.RunnableParams;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 无参数工具调用测试。
 *
 * <p>验证 ReactAgent 能否正确处理无参数的 @Tool 方法（如 getSystemInfo）。
 *
 * @author bigchui
 * 
 */
public class NoParamToolTest {

    /**
     * 测试 1：流式 — 无参数工具调用。
     */
    public static void testStreamNoParamTool() {
        TestConfig.printTestHeader("测试 1：流式 — 无参数工具调用");

        ChatModel chatModel = TestConfig.createChatModel();
        ToolCallback[] tools = ToolCallbacks.from(new TestTools());

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(tools)
                .maxRounds(3)
                .build();

        String query = "获取当前系统信息";
        System.out.println("Q: " + query);
        System.out.println("--- Events Start ---");

        List<AgentStreamEvent> events = new ArrayList<>();
        agent.streamForResult(query, RunnableParams.empty())
                .doOnNext(event -> {
                    TestConfig.printEvent(event);
                })
                .doOnError(err -> System.err.println("Error: " + err.getMessage()))
                .blockLast();

        System.out.println("--- Events End ---");
    }

    /**
     * 测试 2：流式 — 调用streamable mcp无参数工具。
     */
    public static void testCallNoParamTool() {
        TestConfig.printTestHeader("测试 2：流式 — 调用streamable mcp无参数工具");

        HttpClientStreamableHttpTransport streamableTransport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:8004/").endpoint("api/mcp").build();
        McpSyncClient streamableClient = McpClient.sync(streamableTransport)
                .clientInfo(new io.modelcontextprotocol.spec.McpSchema.Implementation("streamable-client", "1.0"))
                .requestTimeout(Duration.ofSeconds(10))
                .build();
        streamableClient.initialize();

        List<McpSyncClient> clients = List.of(streamableClient);

        SyncMcpToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder()
                .mcpClients(clients)
                .build();

        ToolCallback[] callbacks = provider.getToolCallbacks();

        ChatModel chatModel = TestConfig.createZhiPuChatModel();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(callbacks)
                .maxRounds(3)
                .build();

        String query = "帮我查一下商品的总数量";
        System.out.println("Q: " + query);

        List<AgentStreamEvent> events = new ArrayList<>();
        agent.streamForResult(query, RunnableParams.empty())
                .doOnNext(event -> {
                    TestConfig.printEvent(event);
                })
                .doOnError(err -> System.err.println("Error: " + err.getMessage()))
                .blockLast();
        System.out.println("--- Events End ---");
    }

    public static void main(String[] args) {
        System.out.println("===============================================");
        System.out.println("       No-Param Tool Call Test");
        System.out.println("===============================================");

        int testNumber = 2;

        switch (testNumber) {
            case 1 -> testStreamNoParamTool();
            case 2 -> testCallNoParamTool();
            default -> System.out.println("无效的测试编号: " + testNumber);
        }

        System.out.println("\n===============================================");
        System.out.println("       Test Completed");
        System.out.println("===============================================");
    }
}
