package com.agentx.ai.core.stage;

import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.model.StageContext;
import com.agentx.ai.core.model.ToolRecord;
import com.agentx.ai.core.trace.TraceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 执行期上下文。
 *
 * 在一次 {@code stream()} 或 {@code call()} 调用期间存活，
 * 累积收集工具执行记录、最终答案等数据。
 * 在生命周期钩子点通过 {@link #toStageContext()} 生成不可变快照传给 {@link StageOutputProvider}。
 *
 * @author bigchui
 *
 */
public class AgentExecutionContext {

    private final String query;
    private final RunnableParams params;
    private final List<ToolRecord> toolRecords = new ArrayList<>();
    private String answer;
    private TraceManager traceManager;
    private long sessionId;
    private final AtomicLong totalPromptTokens = new AtomicLong(0);
    private final AtomicLong totalCompletionTokens = new AtomicLong(0);

    public AgentExecutionContext(String query, RunnableParams params) {
        this.query = query;
        this.params = params;
    }

    /**
     * 添加一条工具执行记录。
     */
    public void addToolRecord(String toolName, String toolCallId, String arguments, String result) {
        this.toolRecords.add(new ToolRecord(toolName, toolCallId, arguments, result));
    }

    /**
     * 设置最终答案。
     */
    public void setAnswer(String answer) {
        this.answer = answer;
    }

    /**
     * 获取累积的工具执行记录。
     */
    public List<ToolRecord> getToolRecords() {
        return toolRecords;
    }

    public TraceManager getTraceManager() {
        return traceManager;
    }

    public void setTraceManager(TraceManager traceManager) {
        this.traceManager = traceManager;
    }

    public long getSessionId() {
        return sessionId;
    }

    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * 累加本轮 token 用量。
     */
    public void accumulateTokens(long promptTokens, long completionTokens) {
        if (promptTokens > 0) this.totalPromptTokens.addAndGet(promptTokens);
        if (completionTokens > 0) this.totalCompletionTokens.addAndGet(completionTokens);
    }

    /**
     * 从暂停恢复时还原累计 token（先 set 再继续累加）。
     */
    public void restoreTokens(long savedPrompt, long savedCompletion) {
        this.totalPromptTokens.set(savedPrompt);
        this.totalCompletionTokens.set(savedCompletion);
    }

    public long getTotalPromptTokens() {
        return totalPromptTokens.get();
    }

    public long getTotalCompletionTokens() {
        return totalCompletionTokens.get();
    }

    /**
     * 转成不可变快照，传给 StageOutputProvider。
     */
    public StageContext toStageContext() {
        return new StageContext(query, answer, params, List.copyOf(toolRecords));
    }
}
