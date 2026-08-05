package com.agentx.ai.core.stage;

import com.agentx.ai.core.model.AgentStreamEvent;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.trace.TraceManager;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 运行时上下文 — 一次 stream/call 调用期间的临时内存存储，
 * 承载本次调用的所有运行时参数与累积状态，供 ReAct 循环各环节共享访问。
 * 生命周期与一次调用绑定：跨多轮 ReAct 共享同一个实例。
 *
 * @author bigchui
 */
public class AgentRuntimeContext {

    private final String query;
    private final RunnableParams params;

    private TraceManager traceManager;
    private long sessionId;

    private final AtomicLong totalPromptTokens = new AtomicLong(0);
    private final AtomicLong totalCompletionTokens = new AtomicLong(0);
    private final AtomicInteger totalRounds = new AtomicInteger(0);

    private volatile int newMsgStartIndex;
    private volatile List<Message> originalMessagesSnapshot;
    private final AtomicReference<String> terminalStatus = new AtomicReference<>(null);
    /**
     * 终态落库幂等标志。CAS true 一次后，后续 persistOnTerminal 调用直接返回，
     * 避免流式 doFinally 与显式调用双写。
     */
    private final AtomicBoolean persistedFlag = new AtomicBoolean(false);

    /**
     * ReAct 循环的工作消息列表（可变，供 Hook 读取/修改）。
     */
    private List<Message> messages;

    /**
     * 下游事件发射器，Hook 可通过它注入 AgentStreamEvent。
     */
    private Consumer<AgentStreamEvent> emitter;

    public AgentRuntimeContext(String query, RunnableParams params) {
        this.query = query;
        this.params = params;
    }

    public String getQuery() {
        return query;
    }

    public RunnableParams getParams() {
        return params;
    }

    /**
     * 便捷方法：从 params 派生 conversationId。
     */
    public String getConversationId() {
        return params != null ? params.getConversationId() : null;
    }

    /**
     * 便捷方法：从 params 派生 userId。
     */
    public String getUserId() {
        return params != null ? params.getUserId() : null;
    }

    public long getSessionId() {
        return sessionId;
    }

    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * 本次调用新增消息在 messages 列表中的起点，终态落库时据此切片。
     */
    public int getNewMsgStartIndex() {
        return newMsgStartIndex;
    }

    public void setNewMsgStartIndex(int newMsgStartIndex) {
        this.newMsgStartIndex = newMsgStartIndex;
    }

    public List<Message> getOriginalMessagesSnapshot() {
        return originalMessagesSnapshot;
    }

    public void setOriginalMessagesSnapshot(List<Message> originalMessagesSnapshot) {
        this.originalMessagesSnapshot = originalMessagesSnapshot;
    }

    public void appendOriginalMessage(Message message) {
        if (message == null || originalMessagesSnapshot == null) {
            return;
        }
        originalMessagesSnapshot.add(message);
    }

    public void appendOriginalMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty() || originalMessagesSnapshot == null) {
            return;
        }
        originalMessagesSnapshot.addAll(messages);
    }

    /**
     * CAS 标记终态：completed / interrupted / error。只允许设置一次。
     */
    public boolean markTerminal(String status) {
        return terminalStatus.compareAndSet(null, status);
    }

    public String getTerminalStatus() {
        return terminalStatus.get();
    }

    /**
     * CAS 标记已落库。返回 true 表示本次调用抢到落库权，false 表示已被其他路径落库。
     * 流式路径在 sink.tryEmitComplete() 前显式调用，避免 doFinally 时序竞态。
     */
    public boolean tryMarkPersisted() {
        return persistedFlag.compareAndSet(false, true);
    }

    public TraceManager getTraceManager() {
        return traceManager;
    }

    public void setTraceManager(TraceManager traceManager) {
        this.traceManager = traceManager;
    }

    /**
     * 累加本轮 token 用量。
     */
    public void accumulateTokens(long promptTokens, long completionTokens) {
        if (promptTokens > 0) this.totalPromptTokens.addAndGet(promptTokens);
        if (completionTokens > 0) this.totalCompletionTokens.addAndGet(completionTokens);
    }

    /**
     * 从暂停恢复时还原累计 token。
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
     * 轮次自增，每次 scheduleRound 调用。
     */
    public int incrementRound() {
        return totalRounds.incrementAndGet();
    }

    /**
     * 从暂停恢复时还原轮次。
     */
    public void setTotalRounds(int rounds) {
        totalRounds.set(rounds);
    }

    public int getTotalRounds() {
        return totalRounds.get();
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }

    public Consumer<AgentStreamEvent> getEmitter() {
        return emitter;
    }

    public void setEmitter(Consumer<AgentStreamEvent> emitter) {
        this.emitter = emitter;
    }
}
