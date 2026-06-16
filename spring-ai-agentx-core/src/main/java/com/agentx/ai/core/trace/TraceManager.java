package com.agentx.ai.core.trace;

/**
 * 每对话一个实例，持有 sessionId + conversationId + TraceStore。
 * 由 {@link AgentLoopExecutor} 在每次 call/stream 时创建并存入 {@link AgentExecutionContext}。
 *
 * @author bigchui
 */
public class TraceManager {

    private final TraceStore traceStore;
    private final long sessionId;
    private final String conversationId;

    public TraceManager(TraceStore traceStore, long sessionId, String conversationId) {
        this.traceStore = traceStore;
        this.sessionId = sessionId;
        this.conversationId = conversationId;
    }

    public long getSessionId() {
        return sessionId;
    }

    /**
     * 记录一次 LLM 调用 trace。
     */
    public void trace(int round, String inputData, String outputData, String think,
                      int promptTokens, int completionTokens, long durationMs) {
        traceStore.save(sessionId, conversationId, round,
                inputData, outputData, think,
                promptTokens, completionTokens, durationMs, true, null);
    }

    /**
     * 记录一次失败的 LLM 调用 trace。
     */
    public void traceError(int round, String inputData, long durationMs, String errorMessage) {
        traceStore.save(sessionId, conversationId, round,
                inputData, null, null,
                0, 0, durationMs, false, errorMessage);
    }
}
