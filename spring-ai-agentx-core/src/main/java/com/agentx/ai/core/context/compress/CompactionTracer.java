package com.agentx.ai.core.context.compress;

import com.agentx.ai.core.trace.TraceManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 压缩 trace 记录器：把压缩事件（COMPACT span）和它触发的摘要 LLM 调用（LLM span，作为 COMPACT 子节点）
 * 序列化并写入 trace。独立于 ContextCompactor 的策略链执行逻辑，保持单一职责。
 *
 * @author bigchui
 */
public class CompactionTracer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 记录一次压缩：先落 COMPACT span，若有摘要 LLM 调用（L4/L5/L6）再落一条 LLM 子 span。
     *
     * @param summaryPromptTokens     摘要调用的累计输入 token（>0 表示发生过摘要调用）
     * @param summaryCompletionTokens 摘要调用的累计输出 token
     */
    public void record(TraceManager traceManager, int round, String strategy,
                       int beforeTokens, int afterTokens, int beforeMessages, int afterMessages,
                       long summaryPromptTokens, long summaryCompletionTokens, LlmSummarizer summarizer) {
        if (traceManager == null) {
            return;
        }
        long compactId = traceManager.traceCompaction(round, strategy,
                buildCompactionJson(strategy, beforeTokens, afterTokens, beforeMessages, afterMessages), 0);
        if (summaryPromptTokens > 0 && summarizer != null) {
            traceManager.trace(round,
                    buildSummaryInputJson(summarizer.getLastSystemPrompt(), summarizer.getLastUserPrompt()),
                    summarizer.getLastSummary(),
                    null,
                    (int) summaryPromptTokens, (int) summaryCompletionTokens, 0, compactId);
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
