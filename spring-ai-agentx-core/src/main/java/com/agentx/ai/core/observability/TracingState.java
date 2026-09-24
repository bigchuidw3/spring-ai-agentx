package com.agentx.ai.core.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次 Agent 调用的追踪状态。
 *
 * <p>挂在 {@code AgentRuntimeContext.tracingState}（Object 类型，避免核心上下文类
 * 反向依赖 observability 包）上，跨线程显式携带：根 span 在调用线程开启，
 * AfterReasoning/AfterCall 等事件可能在 Reactor boundedElastic 线程触发，
 * micrometer Tracer 的上下文是 ThreadLocal 的，不能依赖自动传播。
 *
 * @author bigchui
 */
public final class TracingState {

    private final Span rootSpan;
    private final Tracer.SpanInScope rootScope;

    /**
     * 每轮推理期间在订阅线程持有的临时 scope（BeforeReasoning 开 / AfterReasoning 关）。
     */
    private volatile Tracer.SpanInScope roundScope;

    /**
     * 在途 tool span，key 为 toolCallId（无 id 时退化为 toolName）。
     */
    private final Map<String, ToolSpan> toolSpans = new ConcurrentHashMap<>();

    public TracingState(Span rootSpan, Tracer.SpanInScope rootScope) {
        this.rootSpan = rootSpan;
        this.rootScope = rootScope;
    }

    public Span getRootSpan() {
        return rootSpan;
    }

    public Tracer.SpanInScope getRootScope() {
        return rootScope;
    }

    public void setRoundScope(Tracer.SpanInScope roundScope) {
        this.roundScope = roundScope;
    }

    public Tracer.SpanInScope getRoundScope() {
        return roundScope;
    }

    public void putToolSpan(String toolCallId, Span span, Tracer.SpanInScope scope) {
        toolSpans.put(toolCallId, new ToolSpan(span, scope));
    }

    public ToolSpan removeToolSpan(String toolCallId) {
        return toolSpans.remove(toolCallId);
    }

    public record ToolSpan(Span span, Tracer.SpanInScope scope) {
    }
}
