package com.agentx.ai.core.agent.internal;

import com.agentx.ai.core.model.AgentStreamEvent;
import com.agentx.ai.core.model.ThinkingMode;
import com.agentx.ai.core.stage.ThinkTagParser;
import org.springframework.ai.chat.messages.AssistantMessage;
import reactor.core.publisher.Sinks;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * ThinkingMode 处理器 — 封装所有思考模式的分支逻辑。
 *
 * <ul>
 *   <li>流式 chunk 的三模式（DISABLED / THINK_TAG / REASONING_CONTENT）分支处理</li>
 *   <li>reasoning_content 提取（metadata + 反射，含反射缓存）</li>
 *   <li>think 标签解析与剥离</li>
 * </ul>
 *
 * @author bigchui
 */
public final class ThinkingModeProcessor {

    private final ThinkingMode thinkingMode;

    // 反射缓存：避免每个 chunk 都执行 getMethod 查找
    private volatile Method cachedReasoningMethod;
    private volatile Class<?> cachedReasoningClass;

    public ThinkingModeProcessor(ThinkingMode thinkingMode) {
        this.thinkingMode = thinkingMode;
    }

    // ==================== 流式 chunk 处理 ====================

    /**
     * 处理流式 chunk 中的文本内容，根据 ThinkingMode 发射对应事件。
     * 替代 AgentLoopExecutor.processChunk 中的三模式分支（DISABLED / THINK_TAG / REASONING_CONTENT）。
     *
     * @param text  chunk 文本内容
     * @param state 轮次状态（inThink、textBuffer、reasoningBuffer）
     * @param sink  事件接收器
     */
    public void processStreamChunk(String text, RoundState state, Sinks.Many<AgentStreamEvent> sink) {
        if (thinkingMode == ThinkingMode.REASONING_CONTENT) {
            // reasoning_content 在 processChunk 中通过 metadata 单独提取，此处仅处理 text
            if (text != null && !text.isEmpty()) {
                state.textBuffer.append(text);
                sink.tryEmitNext(new AgentStreamEvent.Text(text));
            }
        } else if (thinkingMode == ThinkingMode.THINK_TAG) {
            processThinkTagChunk(text, state, sink, true);
        } else {
            // DISABLED：剥离 think 标签，不输出 Thinking 事件
            processThinkTagChunk(text, state, sink, false);
        }
    }

    /**
     * 处理 reasoning_content 模式下从 metadata 中提取的思考内容。
     *
     * @param reasoning reasoning_content 文本
     * @param state     轮次状态
     * @param sink      事件接收器
     */
    public void processReasoningChunk(String reasoning, RoundState state, Sinks.Many<AgentStreamEvent> sink) {
        if (reasoning != null && !reasoning.isEmpty()) {
            state.reasoningBuffer.append(reasoning);
            sink.tryEmitNext(new AgentStreamEvent.Thinking(reasoning));
        }
    }

    /**
     * 处理 forceFinal 场景中的流式 chunk。
     * 替代 AgentLoopExecutor.forceFinalStream 中的三模式分支。
     *
     * @param text          chunk 文本内容
     * @param inThinkHolder inThink 状态持有者（单元素数组）
     * @param answerBuffer  答案累积器
     * @param sink          事件接收器
     */
    public void processForceFinalChunk(String text, boolean[] inThinkHolder,
                                       StringBuilder answerBuffer,
                                       Sinks.Many<AgentStreamEvent> sink) {
        if (thinkingMode == ThinkingMode.REASONING_CONTENT) {
            // REASONING_CONTENT 模式：reasoning 由调用方单独处理，此处仅处理 text
            if (text != null && !text.isEmpty()) {
                answerBuffer.append(text);
                sink.tryEmitNext(new AgentStreamEvent.Text(text));
            }
        } else if (thinkingMode == ThinkingMode.THINK_TAG) {
            processForceFinalThinkTag(text, inThinkHolder, answerBuffer, sink, true);
        } else {
            processForceFinalThinkTag(text, inThinkHolder, answerBuffer, sink, false);
        }
    }

    // ==================== 非流式路径 ====================

    /**
     * 从原始文本中提取 think 标签内的内容。
     */
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

    /**
     * 去除 think 标签，返回纯正文。
     * DISABLED 和 THINK_TAG 模式需要剥离；REASONING_CONTENT 模式已是纯正文。
     */
    public String stripThinkTagsIfNeeded(String answer) {
        if (thinkingMode != ThinkingMode.REASONING_CONTENT && answer != null) {
            return ThinkTagParser.stripThinkTags(answer);
        }
        return answer;
    }

    /**
     * 从 AssistantMessage 中提取 reasoning_content。
     * 优先从 metadata 中取，再通过反射调用消息子类方法。
     * 反射结果已缓存，避免重复查找。
     */
    public String extractReasoningContent(AssistantMessage msg) {
        // 1. metadata 路径（OpenAI 兼容通用路径）
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

        // 2. 反射路径（DeepSeekAssistantMessage 等子类）
        Class<?> msgClass = msg.getClass();
        try {
            if (cachedReasoningMethod == null || cachedReasoningClass != msgClass) {
                try {
                    cachedReasoningMethod = msgClass.getMethod("getReasoningContent");
                    cachedReasoningClass = msgClass;
                } catch (NoSuchMethodException e) {
                    // 标记该类无此方法，后续跳过反射
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
            // 反射调用失败，跳过
        }

        return null;
    }

    /**
     * 累积 reasoning_content（工具调用 chunk 中也可能携带）。
     */
    public void accumulateReasoningContent(AssistantMessage msg, RoundState state) {
        if (thinkingMode != ThinkingMode.REASONING_CONTENT) {
            return;
        }
        String reasoning = extractReasoningContent(msg);
        if (reasoning != null && !reasoning.isEmpty()) {
            state.reasoningBuffer.append(reasoning);
        }
    }

    /**
     * 构建携带 reasoning content 的 properties map。
     */
    public Map<String, Object> buildReasoningProperties(RoundState state) {
        return Map.of("reasoningContent",
                state.reasoningBuffer.isEmpty() ? "" : state.reasoningBuffer.toString());
    }

    // ==================== 私有辅助 ====================

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
                state.textBuffer.append(seg.content());
            } else {
                state.textBuffer.append(seg.content());
                sink.tryEmitNext(new AgentStreamEvent.Text(seg.content()));
            }
        }
    }

    private void processForceFinalThinkTag(String text, boolean[] inThinkHolder,
                                           StringBuilder answerBuffer,
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
                answerBuffer.append(seg.content());
            } else {
                answerBuffer.append(seg.content());
                sink.tryEmitNext(new AgentStreamEvent.Text(seg.content()));
            }
        }
    }
}
