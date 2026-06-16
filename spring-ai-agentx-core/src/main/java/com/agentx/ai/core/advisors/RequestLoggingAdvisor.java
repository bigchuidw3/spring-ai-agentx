package com.agentx.ai.core.advisors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 请求日志 Advisor — 在 LLM 调用前打印近似入参 JSON。
 * <p>
 * 从 {@link ChatClientRequest} 中提取 messages、options、tools，
 * 拼装为 OpenAI 兼容格式的 JSON 并输出到日志（INFO 级别）。
 * model / temperature 等字段通过 {@link ChatModel#getDefaultOptions()} 自动获取。
 * <p>
 * 框架内置 Advisor，通过 {@code ReactAgent.builder().requestLogging(true)} 启用，
 * 调用方无需手动创建。
 *
 * @author bigchui
 */
public class RequestLoggingAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingAdvisor.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** advisor context key: 存入请求 JSON，供 AgentLoopExecutor 读取后记 trace。 */
    public static final String LLM_REQUEST_JSON = "llm.request.json";

    private final ChatModel chatModel;

    public RequestLoggingAdvisor(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String json = buildRequestJson(request, false);
        ChatClientResponse response = chain.nextCall(request);
        if (json != null && response != null) {
            response.context().put(LLM_REQUEST_JSON, json);
        }
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        String json = buildRequestJson(request, true);
        Flux<ChatClientResponse> flux = chain.nextStream(request);
        if (json != null) {
            flux = flux.doOnNext(response -> response.context().put(LLM_REQUEST_JSON, json));
        }
        return flux;
    }

    /**
     * 构建请求 JSON 并打日志。返回 JSON 字符串（失败返回 null）。
     */
    private String buildRequestJson(ChatClientRequest request, boolean stream) {
        try {
            Map<String, Object> requestBody = buildRequestMap(request, stream);
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(requestBody);
            log.debug("\n==================== LLM Request ====================\n{}\n=====================================================", json);
            return json;
        } catch (Exception e) {
            log.warn("[RequestLogging] Failed to serialize request: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> buildRequestMap(ChatClientRequest request, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        Prompt prompt = request.prompt();

        // messages
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            messages.addAll(toMessageMaps(message));
        }
        body.put("messages", messages);

        // stream
        body.put("stream", stream);

        // options: 先用 ChatModel defaultOptions 铺底（model/temperature），再用 prompt options 覆盖
        if (chatModel != null && chatModel.getDefaultOptions() instanceof ChatOptions modelOpts) {
            extractOptions(modelOpts, body);
        }
        ChatOptions options = prompt.getOptions();
        if (options != null) {
            extractOptions(options, body);
        }

        // tools
        if (options instanceof ToolCallingChatOptions toolOptions) {
            extractTools(toolOptions, body);
        }

        return body;
    }

    // ======================== Messages ========================

    /**
     * 将一条 Message 转换为一条或多条 JSON message map。
     * ToolResponseMessage 可能包含多个 tool result，需要展开。
     */
    private List<Map<String, Object>> toMessageMaps(Message message) {
        if (message instanceof ToolResponseMessage toolMessage) {
            return toolMessage.getResponses().stream()
                    .map(response -> {
                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("role", "tool");
                        map.put("tool_call_id", response.id());
                        map.put("name", response.name());
                        map.put("content", response.responseData());
                        return map;
                    })
                    .toList();
        }

        // 其他消息类型：一对一
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", message.getMessageType().name().toLowerCase());

        if (message instanceof AssistantMessage assistantMessage) {
            map.put("content", assistantMessage.getText());
            List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
            if (toolCalls != null && !toolCalls.isEmpty()) {
                List<Map<String, Object>> tcList = new ArrayList<>();
                for (var tc : toolCalls) {
                    Map<String, Object> tcMap = new LinkedHashMap<>();
                    tcMap.put("id", tc.id());
                    tcMap.put("type", tc.type());
                    Map<String, Object> fnMap = new LinkedHashMap<>();
                    fnMap.put("name", tc.name());
                    fnMap.put("arguments", tc.arguments());
                    tcMap.put("function", fnMap);
                    tcList.add(tcMap);
                }
                map.put("tool_calls", tcList);
            }
        } else {
            map.put("content", message.getText());
        }

        return List.of(map);
    }

    // ======================== Options ========================

    private void extractOptions(ChatOptions options, Map<String, Object> body) {
        if (options.getModel() != null) body.put("model", options.getModel());
        if (options.getTemperature() != null) body.put("temperature", options.getTemperature());
        if (options.getTopP() != null) body.put("top_p", options.getTopP());
        if (options.getMaxTokens() != null) body.put("max_tokens", options.getMaxTokens());
        if (options.getFrequencyPenalty() != null) body.put("frequency_penalty", options.getFrequencyPenalty());
        if (options.getPresencePenalty() != null) body.put("presence_penalty", options.getPresencePenalty());
        if (options.getStopSequences() != null && !options.getStopSequences().isEmpty()) {
            body.put("stop", options.getStopSequences());
        }
    }

    // ======================== Tools ========================

    private void extractTools(ToolCallingChatOptions toolOptions, Map<String, Object> body) {
        var toolCallbacks = toolOptions.getToolCallbacks();
        if (toolCallbacks == null || toolCallbacks.isEmpty()) return;

        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolCallback tc : toolCallbacks) {
            tools.add(toToolMap(tc.getToolDefinition()));
        }
        if (!tools.isEmpty()) {
            body.put("tools", tools);
        }
    }

    private Map<String, Object> toToolMap(ToolDefinition def) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", def.name());
        function.put("description", def.description());
        try {
            if (def.inputSchema() != null) {
                function.put("parameters", objectMapper.readValue(def.inputSchema(), Map.class));
            }
        } catch (Exception e) {
            // 解析失败时直接放原始字符串
            function.put("parameters", def.inputSchema());
        }

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    // ======================== Advisor Meta ========================

    @Override
    public String getName() {
        return "RequestLoggingAdvisor";
    }

    /**
     * 最高 order，确保在所有其他 advisor 之后执行，最接近 ChatModel 调用。
     */
    @Override
    public int getOrder() {
        return 100;
    }
}
