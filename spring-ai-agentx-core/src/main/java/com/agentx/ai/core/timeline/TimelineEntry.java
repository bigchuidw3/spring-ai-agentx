package com.agentx.ai.core.timeline;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 时间线条目 — 合并后的渲染就绪结构。
 *
 * 区别于原始 {@link com.agentx.ai.core.model.AgentStreamEvent}：
 * 流式碎片已合并（相邻 Thinking/Text 拼接为一条），ToolStart/ToolEnd 已配对为一条。
 * 每条都是最终态，前端直接遍历渲染，无需重新组装。
 *
 * 序列化时 {@link TimelineSerializer} 手动添加 {@code "type"} 字段标识条目类型，
 * 绕过 Jackson 对 record 多态注解的兼容性问题。
 *
 * @author bigchui
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface TimelineEntry permits
        TimelineEntry.Thinking,
        TimelineEntry.TextEntry,
        TimelineEntry.Tool,
        TimelineEntry.Todo,
        TimelineEntry.ErrorEntry {

    /** 合并后的思考内容 */
    record Thinking(String content) implements TimelineEntry {}

    /** 合并后的文本输出 */
    record TextEntry(String content) implements TimelineEntry {}

    /** 工具调用（Start/End 已配对，带最终状态和结果） */
    record Tool(String toolName,
                String toolCallId,
                String arguments,
                String result,
                String status) implements TimelineEntry {}

    /** 任务列表进度快照 */
    record Todo(List<?> items) implements TimelineEntry {}

    /** 错误信息 */
    record ErrorEntry(String message, String detail) implements TimelineEntry {}
}
