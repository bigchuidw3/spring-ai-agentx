package com.agentx.ai.core.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Spring AI {@link Message} 与 OpenAI 兼容 JSON 之间的双向序列化器。
 *
 * <p>正向 {@link #toJson(List)} 输出 OpenAI Chat Completions 风格的消息列表 JSON，
 * 用于 trace 审计与快照存储。反向 {@link #fromJson(String)} 还原 Message 列表，
 * 用于从中断快照恢复 Agent 执行。
 *
 * <h2>正向：Message → JSON</h2>
 * <pre>
 * AssistantMessage(text, toolCalls=[...])  →  { "role":"assistant", "content":"...", "tool_calls":[...] }
 * UserMessage(text)                        →  { "role":"user", "content":"..." }
 * SystemMessage(text)                      →  { "role":"system", "content":"..." }
 * ToolResponseMessage(responses=[...])     →  多条 { "role":"tool", "tool_call_id":"...", "name":"...", "content":"..." }
 * </pre>
 *
 * <h2>反向：JSON → Message</h2>
 * <pre>
 * { "role":"assistant", "tool_calls":[...] }  →  AssistantMessage(text, toolCalls=[...])
 * { "role":"user" }                           →  UserMessage(content)
 * { "role":"system" }                         →  SystemMessage(content)
 * 连续多条 { "role":"tool" }                    →  合并为单个 ToolResponseMessage(responses=[...])
 * </pre>
 *
 * <p>此类的正向逻辑复用自 {@code RequestLoggingAdvisor.toMessageMaps}，
 * 抽取到独立工具类以便 trace、中断快照、外部调试等多场景共享。
 *
 * @author bigchui
 */
public final class MessageJsonSerializer {

    private static final Logger log = LoggerFactory.getLogger(MessageJsonSerializer.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private MessageJsonSerializer() {
    }

    // ======================== Forward: Message → JSON ========================

    /**
     * 将消息列表序列化为 OpenAI 兼容的 JSON 字符串。
     */
    public static String toJson(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "[]";
        }
        try {
            List<Map<String, Object>> messageMaps = new ArrayList<>(messages.size());
            for (Message message : messages) {
                messageMaps.addAll(toMessageMaps(message));
            }
            return OBJECT_MAPPER.writeValueAsString(messageMaps);
        } catch (Exception e) {
            log.warn("[MessageJsonSerializer] Failed to serialize messages: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 将消息列表转换为 List&lt;Map&gt; 中间格式。
     * 供 JdbcPauseStateStore 等需要嵌套进更大 JSON 结构的场景使用。
     */
    public static List<Map<String, Object>> toMaps(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(messages.size());
        for (Message message : messages) {
            result.addAll(toMessageMaps(message));
        }
        return result;
    }

    /**
     * 将单条 Message 转换为一条或多条 JSON message map。
     * ToolResponseMessage 展开为多条 {@code {role:"tool"}}。
     */
    public static List<Map<String, Object>> toMessageMaps(Message message) {
        if (message instanceof ToolResponseMessage toolMessage) {
            return toToolMessageMaps(toolMessage);
        }
        return List.of(toSingleMessageMap(message));
    }

    private static List<Map<String, Object>> toToolMessageMaps(ToolResponseMessage toolMessage) {
        List<Map<String, Object>> result = new ArrayList<>(toolMessage.getResponses().size());
        for (ToolResponseMessage.ToolResponse response : toolMessage.getResponses()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("role", "tool");
            map.put("tool_call_id", response.id());
            map.put("name", response.name());
            map.put("content", response.responseData());
            result.add(map);
        }
        return result;
    }

    private static Map<String, Object> toSingleMessageMap(Message message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", roleOf(message));

        if (message instanceof AssistantMessage assistantMessage) {
            map.put("content", assistantMessage.getText());
            List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
            if (toolCalls != null && !toolCalls.isEmpty()) {
                map.put("tool_calls", toToolCallMaps(toolCalls));
            }
        } else {
            map.put("content", message.getText());
        }
        return map;
    }

    private static List<Map<String, Object>> toToolCallMaps(List<AssistantMessage.ToolCall> toolCalls) {
        List<Map<String, Object>> result = new ArrayList<>(toolCalls.size());
        for (AssistantMessage.ToolCall tc : toolCalls) {
            Map<String, Object> tcMap = new LinkedHashMap<>();
            tcMap.put("id", tc.id());
            tcMap.put("type", tc.type());

            Map<String, Object> fnMap = new LinkedHashMap<>();
            fnMap.put("name", tc.name());
            fnMap.put("arguments", tc.arguments());
            tcMap.put("function", fnMap);

            result.add(tcMap);
        }
        return result;
    }

    private static String roleOf(Message message) {
        MessageType type = message.getMessageType();
        return type == null ? "user" : type.name().toLowerCase();
    }

    // ======================== Reverse: JSON → Message ========================

    /**
     * 将 List&lt;Map&gt; 中间格式反序列化为 Message 列表。
     * 供 JdbcPauseStateStore 等从嵌套 JSON 提取 messages 的场景使用。
     */
    public static List<Message> fromMaps(List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        return parseMessageList(raw);
    }

    /**
     * 将 OpenAI 兼容 JSON 反序列化为 Spring AI Message 列表。
     *
     * <p>核心规则：连续的 {@code {role:"tool"}} 项合并为单个 {@link ToolResponseMessage}，
     * 与 OpenAI Chat Completions 协议中 tool 消息必须是 assistant.tool_calls 后跟的形式一致。
     */
    public static List<Message> fromJson(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> raw = OBJECT_MAPPER.readValue(json, new TypeReference<>() {});
            return parseMessageList(raw);
        } catch (Exception e) {
            log.warn("[MessageJsonSerializer] Failed to parse messages JSON: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private static List<Message> parseMessageList(List<Map<String, Object>> raw) {
        List<Message> result = new ArrayList<>(raw.size());

        int i = 0;
        while (i < raw.size()) {
            Map<String, Object> item = raw.get(i);
            String role = asString(item.get("role"));

            if ("tool".equalsIgnoreCase(role)) {
                // 收集连续的 tool 项，合并为单个 ToolResponseMessage
                List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                while (i < raw.size()
                        && "tool".equalsIgnoreCase(asString(raw.get(i).get("role")))) {
                    Map<String, Object> toolItem = raw.get(i);
                    responses.add(new ToolResponseMessage.ToolResponse(
                            asString(toolItem.get("tool_call_id")),
                            asString(toolItem.get("name")),
                            asString(toolItem.get("content"))
                    ));
                    i++;
                }
                if (!responses.isEmpty()) {
                    result.add(ToolResponseMessage.builder().responses(responses).build());
                }
                continue;
            }

            result.add(parseSingleMessage(item, role));
            i++;
        }
        return result;
    }

    private static Message parseSingleMessage(Map<String, Object> item, String role) {
        String content = asString(item.get("content"));

        if ("assistant".equalsIgnoreCase(role)) {
            List<AssistantMessage.ToolCall> toolCalls = parseToolCalls(item.get("tool_calls"));
            if (toolCalls != null && !toolCalls.isEmpty()) {
                return AssistantMessage.builder()
                        .content(content != null ? content : "")
                        .toolCalls(toolCalls)
                        .build();
            }
            return new AssistantMessage(content != null ? content : "");
        }
        if ("system".equalsIgnoreCase(role)) {
            return new SystemMessage(content != null ? content : "");
        }
        // 默认按 user 处理（含 role="user" 及无法识别的 role）
        return new UserMessage(content != null ? content : "");
    }

    @SuppressWarnings("unchecked")
    private static List<AssistantMessage.ToolCall> parseToolCalls(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<AssistantMessage.ToolCall> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String id = asString(map.get("id"));
            String type = asString(map.get("type"));
            if (type == null) {
                type = "function";
            }
            String name = null;
            String arguments = null;
            Object fn = map.get("function");
            if (fn instanceof Map<?, ?> fnMap) {
                name = asString(fnMap.get("name"));
                arguments = asString(fnMap.get("arguments"));
            }
            if (arguments == null) {
                arguments = "{}";
            }
            if (name == null) {
                // 没有函数名的 tool_call 无法重建，跳过
                log.warn("[MessageJsonSerializer] Skipping tool_call without function name: id={}", id);
                continue;
            }
            result.add(new AssistantMessage.ToolCall(
                    Objects.requireNonNullElse(id, ""),
                    type,
                    name,
                    arguments));
        }
        return result.isEmpty() ? null : result;
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        return value.toString();
    }
}
