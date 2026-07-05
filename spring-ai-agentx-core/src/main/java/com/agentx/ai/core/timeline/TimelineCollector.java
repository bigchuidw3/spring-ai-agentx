package com.agentx.ai.core.timeline;

import java.util.ArrayList;
import java.util.List;

/**
 * 时间线收集器 — 在事件发射时同步合并，产出渲染就绪的时间线。
 *
 * 合并规则：
 * - Thinking：与上一条同类型则追加内容，否则新建
 * - Text：与上一条同类型则追加内容，否则新建
 * - ToolStart：新建一条 running 状态的工具条目
 * - ToolEnd：倒序找到匹配的 running 条目，补上 result 并标记 completed
 * - TodoProgress / Error：直接追加（天然是完整态，不需合并）
 *
 * 轮次之间一定有 ToolStart/ToolEnd 隔开，所以不同轮的 Think 碎片物理上不相邻，
 * 合并规则天然不会跨轮合并。
 *
 * 生命周期：与 AgentExecutionContext 一致，一次 stream()/call() 调用一个实例。
 *
 * @author bigchui
 */
public class TimelineCollector {

    private final List<TimelineEntry> entries = new ArrayList<>();

    /** Thinking 碎片：与上一条同类型则追加 */
    public void onThinking(String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        TimelineEntry last = lastEntry();
        if (last instanceof TimelineEntry.Thinking t) {
            entries.set(entries.size() - 1, new TimelineEntry.Thinking(t.content() + content));
        } else {
            entries.add(new TimelineEntry.Thinking(content));
        }
    }

    /** Text 碎片：与上一条同类型则追加 */
    public void onText(String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        TimelineEntry last = lastEntry();
        if (last instanceof TimelineEntry.TextEntry t) {
            entries.set(entries.size() - 1, new TimelineEntry.TextEntry(t.content() + content));
        } else {
            entries.add(new TimelineEntry.TextEntry(content));
        }
    }

    /** 工具开始：新建 running 状态条目 */
    public void onToolStart(String toolName, String toolCallId, String arguments) {
        entries.add(new TimelineEntry.Tool(toolName, toolCallId, arguments, null, "running"));
    }

    /** 工具完成：倒序匹配 running 条目，补 result 并标记 completed */
    public void onToolEnd(String toolCallId, String result) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            TimelineEntry entry = entries.get(i);
            if (entry instanceof TimelineEntry.Tool tool
                    && toolCallId.equals(tool.toolCallId())
                    && "running".equals(tool.status())) {
                entries.set(i, new TimelineEntry.Tool(
                        tool.toolName(), tool.toolCallId(), tool.arguments(), result, "completed"));
                return;
            }
        }
    }

    /** 任务进度快照：直接追加 */
    public void onTodoProgress(List<?> items) {
        if (items != null && !items.isEmpty()) {
            entries.add(new TimelineEntry.Todo(items));
        }
    }

    /** 错误：直接追加 */
    public void onError(String message, String detail) {
        entries.add(new TimelineEntry.ErrorEntry(message, detail));
    }

    /** 获取已收集的条目（不可变快照） */
    public List<TimelineEntry> getEntries() {
        return List.copyOf(entries);
    }

    /** 条目是否为空 */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    private TimelineEntry lastEntry() {
        return entries.isEmpty() ? null : entries.get(entries.size() - 1);
    }
}
