package com.agentx.ai.core.agent.internal;

import com.agentx.ai.core.interrupt.SafePoint;
import com.agentx.ai.core.interrupt.PauseReason;
import com.agentx.ai.core.model.AgentStreamEvent;
import com.agentx.ai.core.model.PauseState;
import com.agentx.ai.core.model.PendingToolCall;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.stage.AgentRuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 中断上下文 — 跟踪当前所在的安全点，供用户主动中断时构建 {@link PauseState}。
 *
 * <p>每个流式执行（{@code stream} / {@code resumeStream}）创建一个独立实例，
 * 通过 volatile 字段保证跨线程可见性（LLM 流回调运行在 boundedElastic 线程池）。
 * 非流式路径（{@code call} / {@code resume}）不支持中断，仅支持 {@code stopTask} 强制终止。
 *
 * <h2>安全点流转</h2>
 * <pre>
 * INIT ──scheduleRound()──▶ LLM_STREAMING
 *                          ──finishRound()──▶ TOOL_EXECUTION (有 tool_calls)
 * TOOL_EXECUTION ──工具回调──▶ LLM_STREAMING (下一轮 scheduleRound)
 * </pre>
 * <p>状态机单向不可回归：流一旦启动不再回到 INIT。
 *
 * <p>中断可在任何安全点触发，{@link #buildSnapshot(String, AgentRuntimeContext, long)}
 * 按当前安全点填充 PauseState 字段：
 * <ul>
 *   <li>INIT：流未启动，messages 合法但无 pending（极少触发）</li>
 *   <li>LLM_STREAMING：pendingToolCalls 为空</li>
 *   <li>TOOL_EXECUTION：pendingToolCalls 含正在执行的工具调用</li>
 * </ul>
 *
 * @author bigchui
 */
public class InterruptContext {

    private static final Logger log = LoggerFactory.getLogger(InterruptContext.class);

    private final List<Message> messagesRef;
    private final Sinks.Many<AgentStreamEvent> sink;
    private final RunnableParams params;
    private final String query;

    private volatile SafePoint phase = SafePoint.INIT;
    /**
     * TOOL_EXECUTION 安全点处缓存的待执行工具调用
     */
    private final AtomicReference<List<AssistantMessage.ToolCall>> pendingToolCalls =
            new AtomicReference<>(List.of());

    /**
     * 当前轮文本缓冲区引用（中断时取部分输出并持久化到 agentx_session）
     */
    private volatile StringBuilder textBuffer;
    /**
     * 当前轮推理缓冲区引用（中断时取部分思考并持久化到 agentx_session）
     */
    private volatile StringBuilder reasoningBuffer;

    public InterruptContext(List<Message> messagesRef,
                            Sinks.Many<AgentStreamEvent> sink,
                            RunnableParams params,
                            String query) {
        this.messagesRef = messagesRef;
        this.sink = sink;
        this.params = params;
        this.query = query;
    }

    /**
     * 进入 LLM_STREAMING 安全点（在调用 LLM 之前）。
     */
    public void enterLlmStreaming() {
        this.phase = SafePoint.LLM_STREAMING;
        this.pendingToolCalls.set(List.of());
    }

    /**
     * 进入 TOOL_EXECUTION 安全点（在调用工具之前）。
     */
    public void enterToolExecution(List<AssistantMessage.ToolCall> toolCalls) {
        this.phase = SafePoint.TOOL_EXECUTION;
        this.pendingToolCalls.set(toolCalls == null ? List.of() : List.copyOf(toolCalls));
    }

    public SafePoint getPhase() {
        return phase;
    }

    /**
     * 设置当前轮的文本和推理缓冲区引用（每轮开始时由 scheduleRound 调用）。
     * 中断时从这些缓冲区读取部分输出并持久化到 agentx_session。
     */
    public void setRoundBuffers(StringBuilder text, StringBuilder reasoning) {
        this.textBuffer = text;
        this.reasoningBuffer = reasoning;
    }

    public String getPartialText() {
        return textBuffer != null ? textBuffer.toString() : "";
    }

    public String getPartialReasoning() {
        return reasoningBuffer != null ? reasoningBuffer.toString() : "";
    }

    /**
     * 构建暂停快照。messages 取当前 messages 列表快照（浅拷贝避免后续修改污染）。
     *
     * @param interruptMessage 中断说明消息
     * @param runtimeCtx       运行时上下文（提供 sessionId、token 累计）
     * @param currentRound     当前轮次（来自 AgentLoopExecutor 的 roundCounter）
     * @return PauseState 实例（已填充 reason、interruptPhase、pendingToolCalls 等）
     */
    public PauseState buildSnapshot(String interruptMessage,
                                    AgentRuntimeContext runtimeCtx,
                                    long currentRound) {
        List<Message> messagesSnapshot = new ArrayList<>(messagesRef);

        List<PendingToolCall> pending = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : pendingToolCalls.get()) {
            pending.add(new PendingToolCall(tc.id(), tc.name(), tc.arguments()));
        }

        long sessionId = runtimeCtx != null ? runtimeCtx.getSessionId() : 0L;
        long promptTokens = runtimeCtx != null ? runtimeCtx.getTotalPromptTokens() : 0L;
        long completionTokens = runtimeCtx != null ? runtimeCtx.getTotalCompletionTokens() : 0L;

        return PauseState.builder()
                .messages(messagesSnapshot)
                .params(params)
                .query(query)
                .reason(PauseReason.USER_INTERRUPT)
                .safePoint(phase)
                .interruptMessage(interruptMessage)
                .interruptedAt(System.currentTimeMillis())
                .pendingToolCalls(pending)
                .currentRound((int) currentRound)
                .sessionId(sessionId)
                .totalPromptTokens(promptTokens)
                .totalCompletionTokens(completionTokens)
                .build();
    }

    /**
     * 发射 Paused 事件并完成 sink。被 {@link AgentTaskManager#interrupt} 注册的回调调用。
     */
    public void emitPausedAndComplete(PauseState state) {
        try {
            sink.tryEmitNext(new AgentStreamEvent.Paused(state));
        } catch (Exception e) {
            log.warn("[InterruptContext] Failed to emit Paused: {}", e.getMessage());
        }
        sink.tryEmitComplete();
    }
}
