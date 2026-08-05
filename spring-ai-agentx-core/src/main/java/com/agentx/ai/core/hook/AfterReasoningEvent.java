package com.agentx.ai.core.hook;

import com.agentx.ai.core.stage.AgentRuntimeContext;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

/**
 * 单轮推理结束事件（LLM stream 完成后）。
 *
 * <p>只读：携带本轮 LLM 输出（文本、工具调用决策、token 用量）。
 *
 * @author bigchui
 */
public final class AfterReasoningEvent implements HookEvent {

    private final AgentRuntimeContext runtimeContext;
    private final String text;
    private final List<AssistantMessage.ToolCall> toolCalls;
    private final long round;
    private final long promptTokens;
    private final long completionTokens;
    private final long durationMs;

    public AfterReasoningEvent(AgentRuntimeContext runtimeContext, String text,
                                List<AssistantMessage.ToolCall> toolCalls,
                                long round, long promptTokens, long completionTokens, long durationMs) {
        this.runtimeContext = runtimeContext;
        this.text = text;
        this.toolCalls = toolCalls;
        this.round = round;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.durationMs = durationMs;
    }

    public AgentRuntimeContext getRuntimeContext() {
        return runtimeContext;
    }

    public String getText() {
        return text;
    }

    public List<AssistantMessage.ToolCall> getToolCalls() {
        return toolCalls;
    }

    public long getRound() {
        return round;
    }

    public long getPromptTokens() {
        return promptTokens;
    }

    public long getCompletionTokens() {
        return completionTokens;
    }

    public long getDurationMs() {
        return durationMs;
    }
}
