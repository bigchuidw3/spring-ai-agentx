package com.agentx.ai.core.trace;

import java.sql.Timestamp;

/**
 * 每对话一个实例，持有 sessionId + conversationId + TraceStore。
 * 由 AgentLoopExecutor 在每次 call/stream 时创建并存入 AgentRuntimeContext。
 *
 * <p>created_at 统一在管理器侧捕获事件发生时刻，与实际入库时刻解耦。
 *
 * @author bigchui
 */
public class TraceManager {

    private final TraceStore traceStore;
    private final long sessionId;
    private final String conversationId;
    private final String userId;

    public TraceManager(TraceStore traceStore, long sessionId, String conversationId, String userId) {
        this.traceStore = traceStore;
        this.sessionId = sessionId;
        this.conversationId = conversationId;
        this.userId = userId;
    }

    public long getSessionId() {
        return sessionId;
    }

    /**
     * 记录一次 LLM 调用 trace。
     */
    public void trace(int round, String inputData, String outputData, String think,
                      int promptTokens, int completionTokens, long durationMs) {
        trace(round, inputData, outputData, think, promptTokens, completionTokens, durationMs, null);
    }

    /**
     * 记录一次 LLM 调用 trace，可指定所属 COMPACT span id（压缩摘要 LLM 挂在 COMPACT 下）。
     */
    public void trace(int round, String inputData, String outputData, String think,
                      int promptTokens, int completionTokens, long durationMs, Long compactId) {
        traceStore.save(sessionId, conversationId, userId, round,
                inputData, outputData, think,
                promptTokens, completionTokens, durationMs, true, null,
                new Timestamp(System.currentTimeMillis()), compactId);
    }

    /**
     * 记录一次失败的 LLM 调用 trace。
     */
    public void traceError(int round, String inputData, long durationMs, String errorMessage) {
        traceStore.save(sessionId, conversationId, userId, round,
                inputData, null, null,
                0, 0, durationMs, false, errorMessage,
                new Timestamp(System.currentTimeMillis()), null);
    }

    /**
     * 记录一次工具执行 trace（TOOL Span）。
     *
     * @param round          发起该工具调用的 LLM 轮次
     * @param toolName       工具名
     * @param toolCallId     模型 tool_calls 的调用ID
     * @param arguments      工具入参 JSON
     * @param result         工具结果（失败时可为 null）
     * @param success        是否成功
     * @param durationMs     工具执行耗时（毫秒）
     * @param errorMessage   失败原因（成功时为 null）
     */
    public void traceTool(int round, String toolName, String toolCallId,
                          String arguments, String result,
                          boolean success, long durationMs, String errorMessage) {
        traceStore.saveTool(sessionId, conversationId, userId, round,
                toolName, toolCallId, arguments, result,
                success, durationMs, errorMessage,
                new Timestamp(System.currentTimeMillis()));
    }

    /**
     * 记录一次上下文压缩 trace（COMPACT Span）。
     *
     * @param round       触发压缩的 LLM 轮次
     * @param strategy    压缩策略名（L1-L6）
     * @param summaryJson 压缩详情 JSON（前后 token/消息数等）
     * @param durationMs  压缩耗时（毫秒）
     */
    public long traceCompaction(int round, String strategy, String summaryJson, long durationMs) {
        return traceStore.saveCompaction(sessionId, conversationId, userId, round,
                strategy, summaryJson, durationMs,
                new Timestamp(System.currentTimeMillis()));
    }
}
