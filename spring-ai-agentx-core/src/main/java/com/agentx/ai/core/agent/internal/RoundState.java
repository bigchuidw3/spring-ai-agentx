package com.agentx.ai.core.agent.internal;

import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式轮次执行状态。
 * 从 AgentLoopExecutor 中提取为独立类，供 ThinkingModeProcessor 等协作类共享。
 */
public class RoundState {
    RoundMode mode = RoundMode.TEXT;
    final List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
    final StringBuilder textBuffer = new StringBuilder();
    final StringBuilder reasoningBuffer = new StringBuilder();
    boolean inThink = false;
    long promptTokens = -1;
    long completionTokens = -1;
    String finishReason;
}
