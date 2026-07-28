package com.agentx.ai.core.agent.internal;

import com.agentx.ai.core.model.AgentStreamEvent;
import com.agentx.ai.core.model.ThinkingMode;
import com.agentx.ai.core.stage.ThinkTagParser;
import org.springframework.ai.chat.messages.AssistantMessage;
import reactor.core.publisher.Sinks;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * ThinkingMode 处理器 — 封装思考模式的分支逻辑：
 * 流式 chunk 三模式（DISABLED / THINK_TAG / REASONING_CONTENT）处理、
 * reasoning_content 提取（metadata + 反射，含反射缓存）、think 标签解析与剥离。
 *
 * @author bigchui
 */
public final class ThinkingModeProcessor {

    private final ThinkingMode thinkingMode;

    private volatile Method cachedReasoningMethod;
    private volatile Class<?> cachedReasoningClass;

    public ThinkingModeProcessor(ThinkingMode thinkingMode) {
        this.thinkingMode = thinkingMode;
    }

    public void processStreamChunk(String text, RoundState state,
                                   Sinks.Many<AgentStreamEvent> sink) {
        if (thinkingMode == ThinkingMode.REASONING_CONTENT) {
            if (text != null && !text.isEmpty()) {
                state.textBuffer.append(text);
                sink.tryEmitNext(new AgentStreamEvent.Text(text));
            }
        } else if (thinkingMode == ThinkingMode.THINK_TAG) {
            processThinkTagChunk(text, state, sink, true);
        } else {
            processThinkTagChunk(text, state, sink, false);
        }
    }

    public void processReasoningChunk(String reasoning, RoundState state,
                                      Sinks.Many<AgentStreamEvent> sink) {
        if (reasoning != null && !reasoning.isEmpty()) {
            state.reasoningBuffer.append(reasoning);
            sink.tryEmitNext(new AgentStreamEvent.Thinking(reasoning));
        }
    }

    public void processForceFinalChunk(String text, boolean[] inThinkHolder,
                                       StringBuilder answerBuffer,
                                       StringBuilder reasoningBuffer,
                                       Sinks.Many<AgentStreamEvent> sink) {
        if (thinkingMode == ThinkingMode.REASONING_CONTENT) {
            if (text != null && !text.isEmpty()) {
                answerBuffer.append(text);
                sink.tryEmitNext(new AgentStreamEvent.Text(text));
            }
        } else if (thinkingMode == ThinkingMode.THINK_TAG) {
            processForceFinalThinkTag(text, inThinkHolder, answerBuffer, reasoningBuffer, sink, true);
        } else {
            processForceFinalThinkTag(text, inThinkHolder, answerBuffer, reasoningBuffer, sink, false);
        }
    }

    public String extractThinkContent(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        StringBuilder think = new StringBuilder();
        ThinkTagParser.ParseResult result = ThinkTagParser.parse(input, false);
        for (ThinkTagParser.Segment seg : result.segments()) {
            if (seg.thinking()) {
                think.append(seg.content());
            }
        }
        return think.isEmpty() ? null : think.toString().trim();
    }

    /** 去除 think 标签，返回纯正文。REASONING_CONTENT 模式已是纯正文。 */
    public String stripThinkTagsIfNeeded(String answer) {
        if (thinkingMode != ThinkingMode.REASONING_CONTENT && answer != null) {
            return ThinkTagParser.stripThinkTags(answer);
        }
        return answer;
    }

    /** 从 AssistantMessage 中提取 reasoning_content（metadata 优先，反射兜底）。 */
    public String extractReasoningContent(AssistantMessage msg) {
        Map<String, Object> metadata = msg.getMetadata();
        if (metadata != null) {
            Object rc = metadata.get("reasoningContent");
            if (rc == null) {
                rc = metadata.get("reasoning_content");
            }
            if (rc instanceof String s && !s.isEmpty()) {
                return s;
            }
        }

        Class<?> msgClass = msg.getClass();
        try {
            if (cachedReasoningMethod == null || cachedReasoningClass != msgClass) {
                try {
                    cachedReasoningMethod = msgClass.getMethod("getReasoningContent");
                    cachedReasoningClass = msgClass;
                } catch (NoSuchMethodException e) {
                    cachedReasoningClass = null;
                    return null;
                }
            }
            if (cachedReasoningMethod != null && cachedReasoningClass != null) {
                Object result = cachedReasoningMethod.invoke(msg);
                if (result instanceof String s && !s.isEmpty()) {
                    return s;
                }
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    public void accumulateReasoningContent(AssistantMessage msg, RoundState state) {
        if (thinkingMode != ThinkingMode.REASONING_CONTENT) {
            return;
        }
        String reasoning = extractReasoningContent(msg);
        if (reasoning != null && !reasoning.isEmpty()) {
            state.reasoningBuffer.append(reasoning);
        }
    }

    public Map<String, Object> buildReasoningProperties(RoundState state) {
        return Map.of("reasoningContent",
                state.reasoningBuffer.isEmpty() ? "" : state.reasoningBuffer.toString());
    }

    private void processThinkTagChunk(String text, RoundState state,
                                      Sinks.Many<AgentStreamEvent> sink,
                                      boolean emitThinkingEvents) {
        if (text == null || text.isEmpty()) {
            return;
        }
        ThinkTagParser.ParseResult result = ThinkTagParser.parse(text, state.inThink);
        state.inThink = result.inThink();
        for (ThinkTagParser.Segment seg : result.segments()) {
            if (seg.thinking()) {
                if (emitThinkingEvents) {
                    sink.tryEmitNext(new AgentStreamEvent.Thinking(seg.content()));
                }
                state.reasoningBuffer.append(seg.content());
            } else {
                state.textBuffer.append(seg.content());
                sink.tryEmitNext(new AgentStreamEvent.Text(seg.content()));
            }
        }
    }

    private void processForceFinalThinkTag(String text, boolean[] inThinkHolder,
                                           StringBuilder answerBuffer,
                                           StringBuilder reasoningBuffer,
                                           Sinks.Many<AgentStreamEvent> sink,
                                           boolean emitThinkingEvents) {
        if (text == null || text.isEmpty()) {
            return;
        }
        ThinkTagParser.ParseResult result = ThinkTagParser.parse(text, inThinkHolder[0]);
        inThinkHolder[0] = result.inThink();
        for (ThinkTagParser.Segment seg : result.segments()) {
            if (seg.thinking()) {
                if (emitThinkingEvents) {
                    sink.tryEmitNext(new AgentStreamEvent.Thinking(seg.content()));
                }
                reasoningBuffer.append(seg.content());
            } else {
                answerBuffer.append(seg.content());
                sink.tryEmitNext(new AgentStreamEvent.Text(seg.content()));
            }
        }
    }
}
