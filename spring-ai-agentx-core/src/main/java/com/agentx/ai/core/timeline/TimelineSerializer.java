package com.agentx.ai.core.timeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 时间线序列化工具，将 {@link TimelineEntry} 列表转为 JSON 字符串。
 *
 * 手动添加 {@code "type"} 字段，绕过 Jackson 对 record 多态注解的兼容性问题。
 *
 * @author bigchui
 */
public final class TimelineSerializer {

    private static final Logger log = LoggerFactory.getLogger(TimelineSerializer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TimelineSerializer() {
    }

    /**
     * 序列化时间线条目列表为 JSON 字符串。
     *
     * @param entries 时间线条目
     * @return JSON 字符串，空列表或异常时返回 null
     */
    public static String toJson(List<TimelineEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        try {
            ArrayNode array = MAPPER.createArrayNode();
            for (TimelineEntry entry : entries) {
                ObjectNode node = (ObjectNode) MAPPER.valueToTree(entry);
                node.put("type", typeName(entry));
                array.add(node);
            }
            return MAPPER.writeValueAsString(array);
        } catch (Exception e) {
            log.warn("时间线序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private static String typeName(TimelineEntry entry) {
        return switch (entry) {
            case TimelineEntry.Thinking ignored -> "thinking";
            case TimelineEntry.TextEntry ignored -> "text";
            case TimelineEntry.Tool ignored -> "tool";
            case TimelineEntry.Todo ignored -> "todo";
            case TimelineEntry.ErrorEntry ignored -> "error";
        };
    }
}
