package com.agentx.ai.core.observability;

import com.agentx.ai.core.hook.AfterCallEvent;
import com.agentx.ai.core.hook.AfterReasoningEvent;
import com.agentx.ai.core.hook.AfterToolExecutionEvent;
import com.agentx.ai.core.hook.AgentHook;
import com.agentx.ai.core.hook.BeforeCallEvent;
import com.agentx.ai.core.hook.BeforeReasoningEvent;
import com.agentx.ai.core.hook.BeforeToolExecutionEvent;
import com.agentx.ai.core.hook.ErrorEvent;
import com.agentx.ai.core.hook.HookEvent;
import com.agentx.ai.core.stage.AgentRuntimeContext;
import com.agentx.ai.core.utils.MessageJsonSerializer;
import com.alibaba.fastjson2.JSON;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 调用链追踪 Hook — 监听 Agent 生命周期事件，产出 trace 树。
 *
 * <p>所有内容（原文、不截断）集中记录在根 span，LLM 层 span 只留骨架：
 * <pre>
 * agentx.agent                    ← 一次调用（根 span）
 * ├─ agentx.round.N.*             ← 每轮：prompt / output / tool_calls
 * ├─ agentx.tool.&lt;name&gt;           ← 每个工具：arguments / result / success / duration_ms
 * └─ gen_ai.*（Spring AI 产出）    ← 每轮 LLM 骨架：模型名 / token / 耗时
 * </pre>
 *
 * <p>用法：{@code ReactAgent.builder().hooks(new TracingHook(tracer))}
 * （tracer 来自宿主的 micrometer-tracing + OTel bridge 配置）。
 *
 * @author bigchui
 */
public class TracingHook implements AgentHook {

    // ==================== span 名称 ====================

    /**
     * 根 span 名前缀，完整名字形如 agentx_&lt;conversationId&gt;（会话级标识，稳定且不泄漏用户内容）。
     */
    public static final String SPAN_AGENT_PREFIX = "agentx_";
    /**
     * 工具 span 前缀：agentx.tool.&lt;工具名&gt;。
     */
    public static final String SPAN_TOOL = "agentx.tool.";

    // ==================== 根 span 属性 ====================

    /**
     * 调用总耗时（毫秒）。
     */
    public static final String KEY_FINAL_DURATION_MS = "agentx.final.duration_ms";
    /**
     * 最后一次错误信息（含重试期间的中间错误，后写覆盖）。
     */
    public static final String KEY_ERROR = "agentx.error";
    /**
     * 总轮次。
     */
    public static final String KEY_TOTAL_ROUNDS = "agentx.total.rounds";
    /**
     * 累计 prompt token（输入消耗）。
     */
    public static final String KEY_USAGE_PROMPT_TOKENS = "agentx.usage.prompt_tokens";
    /**
     * 累计 completion token（输出消耗）。
     */
    public static final String KEY_USAGE_COMPLETION_TOKENS = "agentx.usage.completion_tokens";
    /**
     * 累计总 token（prompt + completion），与 LLM span 的 usage.total_tokens 对齐。
     */
    public static final String KEY_USAGE_TOTAL_TOKENS = "agentx.usage.total_tokens";

    // ==================== 轮次属性（前缀 + 轮次号 + 字段） ====================

    /**
     * 轮次属性前缀，完整 key 形如 agentx.round.3.output。
     */
    public static final String KEY_ROUND_PREFIX = "agentx.round.";

    // ==================== 工具 span 属性 ====================

    /**
     * 工具属性前缀（与工具 span 名共用），完整 key 形如 agentx.tool.result。
     */
    public static final String KEY_TOOL_PREFIX = SPAN_TOOL;
    /**
     * 工具调用的 call id（模型侧标识）。
     */
    public static final String KEY_TOOL_CALL_ID = "call_id";
    /**
     * 工具执行是否成功。
     */
    public static final String KEY_TOOL_SUCCESS = "success";
    /**
     * 工具执行耗时（毫秒）。
     */
    public static final String KEY_TOOL_DURATION_MS = "duration_ms";

    /**
     * Opik 平台的元数据前缀（conversationId / userId / sessionId / otelTraceId）。
     */
    public static final String OPIK_METADATA_PREFIX = "opik.metadata.";

    private final Tracer tracer;

    public TracingHook(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public HookEvent onEvent(HookEvent event) {
        return switch (event) {
            case BeforeCallEvent e -> startRoot(e.getRuntimeContext());
            case BeforeReasoningEvent e -> beginRound(e);
            case BeforeToolExecutionEvent e -> startTool(e);
            case AfterToolExecutionEvent e -> endTool(e);
            case AfterReasoningEvent e -> endRound(e);
            case ErrorEvent e -> recordError(e);
            case AfterCallEvent e -> finishRoot(e.getRuntimeContext(), e.getFinalAnswer(), e.getDurationMs());
            default -> event;
        };
    }

    /**
     * 调用开始：开根 span，记录用户问题与会话标识。
     */
    private HookEvent startRoot(AgentRuntimeContext ctx) {
        if (ctx.getTracingState() != null) {
            return null; // resume 重入时已有根 span，不重复开启
        }
        String conversationId = ctx.getConversationId() != null ? ctx.getConversationId() : "unknown";
        Span span = tracer.nextSpan().name(SPAN_AGENT_PREFIX + conversationId);
        tagIfPresent(span, ChatContentObservationHandler.KEY_INPUT_VALUE, ctx.getQuery());
        tagIfPresent(span, OPIK_METADATA_PREFIX + "conversationId", ctx.getConversationId());
        tagIfPresent(span, OPIK_METADATA_PREFIX + "userId", ctx.getUserId());
        if (ctx.getSessionId() > 0) {
            span.tag(OPIK_METADATA_PREFIX + "sessionId", Long.toString(ctx.getSessionId()));
        }
        // OTel traceId 与 Opik 平台自生成的 trace id 无对应，写入后可在 Opik 按日志里的 traceId 检索
        tagIfPresent(span, OPIK_METADATA_PREFIX + "otelTraceId", span.context().traceId());
        span.start();
        Tracer.SpanInScope scope = tracer.withSpan(span);
        ctx.setTracingState(new TracingState(span, scope));
        return null;
    }

    /**
     * 每轮推理开始：记录本轮 prompt 原文，并在订阅线程进入根 span scope
     * （后续轮次在 Reactor 回调线程订阅，无 scope 会丢失 parent 而独立成 trace）。
     */
    private HookEvent beginRound(BeforeReasoningEvent e) {
        AgentRuntimeContext ctx = e.getRuntimeContext();
        TracingState state = state(ctx);
        if (state == null) {
            return null;
        }
        List<Message> messages = ctx.getMessages();
        if (messages != null && !messages.isEmpty()) {
            // 此刻上下文压缩已完成、消息已组装，即发给模型的原样
            state.getRootSpan().tag(roundKey(e.getRound(), "prompt"),
                    MessageJsonSerializer.toJson(messages));
        }
        if (state.getRoundScope() == null && tracer.currentSpan() != state.getRootSpan()) {
            state.setRoundScope(tracer.withSpan(state.getRootSpan()));
        }
        return null;
    }

    /**
     * 每轮推理结束：记录输出原文与 tool_calls 指令，关闭轮次 scope
     * （close 在 Reactor 线程执行时恢复的上下文本就为空，无副作用）。
     */
    private HookEvent endRound(AfterReasoningEvent e) {
        TracingState state = state(e.getRuntimeContext());
        if (state == null) {
            return null;
        }
        Span root = state.getRootSpan();
        root.event("round " + e.getRound() + " completed: promptTokens=" + e.getPromptTokens()
                + ", completionTokens=" + e.getCompletionTokens() + ", durationMs=" + e.getDurationMs());
        tagIfPresent(root, roundKey(e.getRound(), "output"), e.getText());
        if (e.getToolCalls() != null && !e.getToolCalls().isEmpty()) {
            // 工具调用轮：模型不输出正文，只发出 tool_calls 指令——记录指令原文
            List<Map<String, String>> calls = new ArrayList<>();
            for (var call : e.getToolCalls()) {
                calls.add(Map.of("name", call.name(),
                        "arguments", call.arguments() == null ? "" : call.arguments()));
            }
            root.tag(roundKey(e.getRound(), "tool_calls"), JSON.toJSONString(calls));
        }
        Tracer.SpanInScope roundScope = state.getRoundScope();
        if (roundScope != null) {
            state.setRoundScope(null);
            try {
                roundScope.close();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * 工具执行前：开工具 span（显式挂根 span 为 parent，防 Reactor 线程断链）。
     */
    private HookEvent startTool(BeforeToolExecutionEvent e) {
        TracingState state = state(e.getRuntimeContext());
        if (state == null) {
            return null;
        }
        Span span = tracer.nextSpan(state.getRootSpan()).name(SPAN_TOOL + e.getToolName());
        tagIfPresent(span, KEY_TOOL_PREFIX + KEY_TOOL_CALL_ID, e.getToolCallId());
        tagIfPresent(span, ChatContentObservationHandler.KEY_INPUT_VALUE, e.getArguments());
        span.start();
        // Before/AfterTool 在同一线程配对，scope 使 SubAgent 与工具内部 LLM 调用自动挂到本 span 下
        Tracer.SpanInScope scope = tracer.withSpan(span);
        state.putToolSpan(toolSpanKey(e), span, scope);
        return null;
    }

    /**
     * 工具执行后：记录参数/结果/成败/耗时，关闭工具 span。
     */
    private HookEvent endTool(AfterToolExecutionEvent e) {
        TracingState state = state(e.getRuntimeContext());
        if (state == null) {
            return null;
        }
        TracingState.ToolSpan toolSpan = state.removeToolSpan(toolSpanKey(e));
        if (toolSpan == null) {
            return null;
        }
        try {
            toolSpan.scope().close();
        } catch (Exception ignored) {
        }
        Span span = toolSpan.span();
        span.tag(KEY_TOOL_PREFIX + KEY_TOOL_SUCCESS, e.isSuccess());
        span.tag(KEY_TOOL_PREFIX + KEY_TOOL_DURATION_MS, e.getDurationMs());
        tagIfPresent(span, ChatContentObservationHandler.KEY_OUTPUT_VALUE, e.getResult());
        span.end();
        return null;
    }

    /**
     * LLM 调用出错：记录错误信息；根 span 不在此结束（AfterCall 保证终态恰好触发一次）。
     */
    private HookEvent recordError(ErrorEvent e) {
        TracingState state = state(e.getRuntimeContext());
        if (state == null) {
            return null;
        }
        String message = e.getError() != null ? e.getError().toString() : "unknown";
        state.getRootSpan().event("error[" + e.getPhase() + "] attempt=" + (e.getRetryAttempt() + 1)
                + (e.isWillRetry() ? " (will retry)" : " (terminal)") + ": " + message);
        tagIfPresent(state.getRootSpan(), KEY_ERROR, message);
        return null;
    }

    /**
     * 调用结束：记录最终答案与汇总，关闭根 span。
     */
    private HookEvent finishRoot(AgentRuntimeContext ctx, String finalAnswer, long durationMs) {
        TracingState state = state(ctx);
        if (state == null) {
            return null;
        }
        ctx.setTracingState(null); // 幂等
        try {
            state.getRootScope().close();
        } catch (Exception ignored) {
            // 流式场景 AfterCall 可能在 Reactor 线程触发，close 属尽力清理
        }
        Span root = state.getRootSpan();
        root.tag(KEY_FINAL_DURATION_MS, durationMs);
        root.tag(KEY_TOTAL_ROUNDS, ctx.getTotalRounds());
        root.tag(KEY_USAGE_PROMPT_TOKENS, ctx.getTotalPromptTokens());
        root.tag(KEY_USAGE_COMPLETION_TOKENS, ctx.getTotalCompletionTokens());
        root.tag(KEY_USAGE_TOTAL_TOKENS, ctx.getTotalPromptTokens() + ctx.getTotalCompletionTokens());
        tagIfPresent(root, ChatContentObservationHandler.KEY_OUTPUT_VALUE, finalAnswer);
        root.end();
        return null;
    }

    /**
     * 轮次属性 key：agentx.round.&lt;轮次号&gt;.&lt;字段&gt;。
     */
    private static String roundKey(long round, String field) {
        return KEY_ROUND_PREFIX + round + "." + field;
    }

    /**
     * 工具 span 存取 key：优先 toolCallId，缺省退化为 toolName。
     */
    private static String toolSpanKey(BeforeToolExecutionEvent e) {
        return e.getToolCallId() != null ? e.getToolCallId() : e.getToolName();
    }

    private static String toolSpanKey(AfterToolExecutionEvent e) {
        return e.getToolCallId() != null ? e.getToolCallId() : e.getToolName();
    }

    private TracingState state(AgentRuntimeContext ctx) {
        Object s = ctx.getTracingState();
        return s instanceof TracingState ts ? ts : null;
    }

    private static void tagIfPresent(Span span, String key, String value) {
        if (value != null && !value.isEmpty()) {
            span.tag(key, value);
        }
    }
}
