package com.agentx.ai.core.context.compress;

import com.agentx.ai.core.trace.TraceManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 压缩 trace 记录器：把压缩事件（COMPACT span）和它触发的摘要 LLM 调用（LLM span，作为 COMPACT 子节点）
 * 序列化并写入 trace。独立于 ContextCompactor 的策略链执行逻辑，保持单一职责。
 *
 * <p>同时向两条通道记录：自建审计（{@link TraceManager}）与 OTel（{@link Tracer}，可选）。
 *
 * @author bigchui
 */
public class CompactionTracer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 宿主传入的 OTel Tracer；为 null 时不产出 OTel span（仅自建审计）。 */
    private final Tracer tracer;

    public CompactionTracer(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * 记录一次压缩：先落 COMPACT span，若有摘要 LLM 调用（L4/L5/L6）再落一条 LLM 子 span。
     *
     * @param summaryPromptTokens     摘要调用的累计输入 token（>0 表示发生过摘要调用）
     * @param summaryCompletionTokens 摘要调用的累计输出 token
     * @param durationMs              压缩总耗时（毫秒）
     */
    public void record(TraceManager traceManager, int round, String strategy,
                       int beforeTokens, int afterTokens, int beforeMessages, int afterMessages,
                       long summaryPromptTokens, long summaryCompletionTokens, LlmSummarizer summarizer,
                       long durationMs) {
        Span compactSpan = tracer != null ? tracer.nextSpan().name("agentx.compact") : null;
        if (compactSpan != null) {
            compactSpan.tag("strategy", strategy);
            compactSpan.tag("before_tokens", beforeTokens);
            compactSpan.tag("after_tokens", afterTokens);
            compactSpan.tag("before_messages", beforeMessages);
            compactSpan.tag("after_messages", afterMessages);
            compactSpan.tag("duration_ms", durationMs);
            compactSpan.start();
        }
        try {
            if (traceManager == null) {
                return;
            }
            long compactId = traceManager.traceCompaction(round, strategy,
                    buildCompactionJson(strategy, beforeTokens, afterTokens, beforeMessages, afterMessages), durationMs);
            if (summaryPromptTokens > 0 && summarizer != null) {
                traceManager.trace(round,
                        buildSummaryInputJson(summarizer.getLastSystemPrompt(), summarizer.getLastUserPrompt()),
                        summarizer.getLastSummary(),
                        null,
                        (int) summaryPromptTokens, (int) summaryCompletionTokens,
                        summarizer.getLastDurationMs(), compactId);
            }
        } finally {
            if (compactSpan != null) {
                compactSpan.end();
            }
        }
    }

    private String buildCompactionJson(String strategy, int beforeTokens, int afterTokens,
                                       int beforeMessages, int afterMessages) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("strategy", strategy);
        data.put("beforeTokens", beforeTokens);
        data.put("afterTokens", afterTokens);
        data.put("beforeMessages", beforeMessages);
        data.put("afterMessages", afterMessages);
        return toJson(data);
    }

    private String buildSummaryInputJson(String systemPrompt, String userPrompt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("system", systemPrompt);
        data.put("user", userPrompt);
        return toJson(data);
    }

    private String toJson(Map<String, Object> data) {
        try {
            return MAPPER.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
