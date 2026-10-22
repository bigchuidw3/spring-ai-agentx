package com.agentx.ai.core.agent.internal;

import com.agentx.ai.core.context.TokenEstimator;
import com.agentx.ai.core.interrupt.PauseReason;
import com.agentx.ai.core.interrupt.PauseStateStore;
import com.agentx.ai.core.memory.store.ConversationStore;
import com.agentx.ai.core.memory.store.SessionMessageStore;
import com.agentx.ai.core.memory.util.MemoryPersistor;
import com.agentx.ai.core.model.PauseState;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.model.TimedMessage;
import com.agentx.ai.core.stage.AgentRuntimeContext;
import com.agentx.ai.core.trace.TraceManager;
import com.agentx.ai.core.trace.TraceStore;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.lang.Nullable;
import reactor.core.publisher.SignalType;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话/Trace/暂停状态的持久化入口，集中处理所有落库副作用。
 * AgentLoopExecutor 在 init、resume、终态分支中委托本类完成 DB 写入。
 *
 * @author bigchui
 */
public class SessionPersister {

    private static final Logger log = LoggerFactory.getLogger(SessionPersister.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final boolean enableSession;
    private final boolean enableTrace;
    private final ConversationStore conversationStore;
    private final SessionMessageStore sessionMessageStore;
    private final TraceStore traceStore;
    private final PauseStateStore stateStore;
    private final MemoryPersistor memoryPersistor;
    private final String agentKey;
    private final String parentSessionId;
    private final String parentToolCallId;

    public SessionPersister(boolean enableSession, boolean enableTrace,
                            ConversationStore conversationStore,
                            SessionMessageStore sessionMessageStore,
                            TraceStore traceStore,
                            PauseStateStore stateStore,
                            MemoryPersistor memoryPersistor,
                            String agentKey,
                            String parentSessionId, String parentToolCallId) {
        this.enableSession = enableSession;
        this.enableTrace = enableTrace;
        this.conversationStore = conversationStore;
        this.sessionMessageStore = sessionMessageStore;
        this.traceStore = traceStore;
        this.stateStore = stateStore;
        this.memoryPersistor = memoryPersistor;
        this.agentKey = agentKey != null ? agentKey : "master";
        this.parentSessionId = parentSessionId;
        this.parentToolCallId = parentToolCallId;
    }

    /** 是否 master 分区（只有 master 写 conversation 行、触发长期记忆）。 */
    private boolean isMaster() {
        return "master".equals(agentKey);
    }

    public String getAgentKey() {
        return agentKey;
    }

    public String getParentToolCallId() {
        return parentToolCallId;
    }

    /**
     * 生成 sessionId、写开局记录、按需创建 TraceManager。
     */
    public void initSession(AgentRuntimeContext execCtx, RunnableParams params, String query) {
        long sessionId = IdWorker.getId();
        execCtx.setSessionId(sessionId);

        String conversationId = params != null ? params.getConversationId() : null;
        String userId = params != null ? params.getUserId() : null;

        if (isMaster() && enableSession && conversationStore != null && conversationId != null) {
            conversationStore.saveStart(conversationId, sessionId, userId, query);
        }

        if (traceStore == null || !enableTrace || conversationId == null) return;
        execCtx.setTraceManager(new TraceManager(traceStore, sessionId, conversationId, userId,
                agentKey, parentToolCallId));
    }

    /**
     * 从 PauseState 恢复会话：HITL 复用原 sessionId，USER_INTERRUPT 新建会话。
     */
    public String initSessionFromResume(AgentRuntimeContext execCtx, PauseState state,
                                        @Nullable String resumeQuery) {
        boolean isInterruptResume = state.getReason() == PauseReason.USER_INTERRUPT
                && resumeQuery != null && !resumeQuery.isBlank();
        execCtx.restoreTokens(state.getTotalPromptTokens(), state.getTotalCompletionTokens());
        // 恢复运行时轮次计数，保证跨暂停/恢复的 rounds 与 trace round 连续
        execCtx.setTotalRounds(state.getCurrentRound());

        if (isInterruptResume) {
            long newSessionId = IdWorker.getId();
            execCtx.setSessionId(newSessionId);
            String conversationId = state.getParams() != null ? state.getParams().getConversationId() : null;
            String userId = state.getParams() != null ? state.getParams().getUserId() : null;
            if (isMaster() && enableSession && conversationStore != null && conversationId != null) {
                conversationStore.saveStart(conversationId, newSessionId, userId, resumeQuery);
            }
            if (traceStore != null && enableTrace && conversationId != null) {
                execCtx.setTraceManager(new TraceManager(traceStore, newSessionId, conversationId, userId,
                        agentKey, parentToolCallId));
            }
            return resumeQuery;
        }

        execCtx.setSessionId(state.getSessionId());
        if (traceStore != null && enableTrace) {
            RunnableParams params = state.getParams();
            String conversationId = params != null ? params.getConversationId() : null;
            String userId = params != null ? params.getUserId() : null;
            execCtx.setTraceManager(new TraceManager(traceStore, state.getSessionId(), conversationId, userId,
                    agentKey, parentToolCallId));
        }
        return state.getQuery();
    }

    /**
     * 累加 token 并写一条 LLM 调用 trace。
     */
    public void recordTrace(AgentRuntimeContext execCtx, int round, String requestJson,
                            String outputData, String think,
                            long promptTokens, long completionTokens, long durationMs) {
        execCtx.accumulateTokens(promptTokens, completionTokens);
        log.debug("[TRACE] round={}, prompt={}, completion={}, totalPrompt={}, totalCompletion={}",
                round, promptTokens, completionTokens,
                execCtx.getTotalPromptTokens(), execCtx.getTotalCompletionTokens());
        TraceManager tm = execCtx.getTraceManager();
        if (tm == null) return;
        tm.trace(round, requestJson, outputData, think,
                (int) promptTokens, (int) completionTokens, durationMs);
    }

    /**
     * 记录一次被中断的 LLM 调用 trace（用户主动中断流式输出时）。
     *
     * <p>流式被中断时 usage 尚未返回（OpenAI/DeepSeek 流式的 usage 在最后一帧），
     * token 无法精确取得，改用 {@link TokenEstimator} 估算：prompt 按完整 messages 估算、
     * completion 按已产出的部分正文 + 部分思考估算。估算值故意偏高，保证 conversation
     * 的 token 累计与成本量级正确，同时让 agentx_trace 保留本轮记录（轨迹页签可见）。
     */
    public void recordTraceInterrupted(AgentRuntimeContext execCtx, int round,
                                       String requestJson, String partialText, String partialReasoning,
                                       List<Message> messages, long durationMs) {
        int promptTokens = TokenEstimator.estimateTokens(messages);
        int completionTokens = TokenEstimator.estimateTokens(partialText)
                + TokenEstimator.estimateTokens(partialReasoning);
        recordTrace(execCtx, round, requestJson, partialText, partialReasoning,
                promptTokens, completionTokens, durationMs);
    }

    /**
     * 记录一次 LLM 调用失败：错误消息追加到 original_messages（chat 页面刷新后还原错误气泡），
     * 并落一条 success=0 的 LLM trace span（审计）。错误消息只进 original_messages、不进工作链，避免污染 LLM 上下文。
     */
    public void recordLlmError(AgentRuntimeContext execCtx, String requestJson, int round, int retryAttempt, int maxRetries, String errorMessage, long durationMs) {
        String title = retryAttempt < maxRetries
                ? "LLM 调用失败，正在重试 (" + (retryAttempt + 1) + "/" + maxRetries + ")"
                : "LLM 调用失败（已重试 " + maxRetries + " 次）";

        if (enableSession && execCtx.getOriginalMessagesSnapshot() != null) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("errorType", "error");
            if (errorMessage != null && !errorMessage.isBlank()) {
                props.put("errorDetail", errorMessage);
            }
            execCtx.appendOriginalMessage(AssistantMessage.builder()
                    .content(title)
                    .properties(props)
                    .build());
        }

        TraceManager tm = execCtx.getTraceManager();
        if (tm != null) {
            tm.traceError(round, requestJson, durationMs, errorMessage);
        }
    }

    /**
     * 将工具调用列表序列化为 trace 用的 JSON。
     */
    public String serializeToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        try {
            var list = toolCalls.stream().map(tc -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", tc.id());
                map.put("type", tc.type());
                map.put("name", tc.name());
                map.put("arguments", tc.arguments());
                return map;
            }).toList();
            return objectMapper.writeValueAsString(Map.of("tool_calls", list));
        } catch (Exception e) {
            log.debug("[TraceStore] Failed to serialize tool calls: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 终态批量落库：写新增消息、更新会话状态、按需触发记忆持久化。靠 CAS 保证只写一次。
     */
    public void persistOnTerminal(AgentRuntimeContext execCtx, List<Message> messages, SignalType signal) {
        String conversationId = execCtx.getConversationId();
        String userId = execCtx.getUserId();
        if (conversationId == null || !enableSession || sessionMessageStore == null) {
            return;
        }
        if (!execCtx.tryMarkPersisted()) {
            return;
        }

        String status = execCtx.getTerminalStatus();
        if (status == null) {
            status = switch (signal) {
                case ON_COMPLETE -> "completed";
                case ON_ERROR -> "error";
                case CANCEL -> "interrupted";
                default -> "interrupted";
            };
        }

        try {
            int start = execCtx.getNewMsgStartIndex();
            List<TimedMessage> originalSnapshot = execCtx.getOriginalMessagesSnapshot();
            List<TimedMessage> thisCallTimed = List.of();
            if (originalSnapshot != null && start < originalSnapshot.size()) {
                thisCallTimed = originalSnapshot.subList(start, originalSnapshot.size());
                sessionMessageStore.appendMessages(
                        conversationId, agentKey, execCtx.getSessionId(), userId,
                        "original_messages", thisCallTimed, parentSessionId, parentToolCallId);
            }
            // working 视图按本次调用开始时间统一打点，避免改写历史消息的原始时间
            LocalDateTime callStarted = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(execCtx.getCallStartedAt()), ZoneId.systemDefault());
            sessionMessageStore.replaceMessages(
                    conversationId, agentKey, execCtx.getSessionId(), userId,
                    "working_messages", messages, parentSessionId, parentToolCallId, callStarted);
            if (isMaster() && conversationStore != null) {
                long[] toolStats = countToolSpans(execCtx);
                conversationStore.updateTerminal(execCtx.getSessionId(), status,
                        execCtx.getTotalRounds(),
                        (int) toolStats[0], (int) toolStats[1],
                        execCtx.getTotalPromptTokens(), execCtx.getTotalCompletionTokens(),
                        System.currentTimeMillis() - execCtx.getCallStartedAt(),
                        execCtx.getModelName());
            }
            if (isMaster() && "completed".equals(status) && memoryPersistor != null && !thisCallTimed.isEmpty()) {
                memoryPersistor.persist(execCtx.getParams(),
                        thisCallTimed.stream().map(TimedMessage::message).toList());
            }
        } catch (Exception e) {
            log.error("Failed to persist terminal session: {}", e.getMessage());
        }
    }

    /**
     * 统计本次调用的工具执行次数与失败次数：从 trace 的 TOOL Span 按 session_id 计数，
     * HITL 暂停/恢复复用同一 session 时天然累计。trace 未启用时返回 0。
     */
    private long[] countToolSpans(AgentRuntimeContext execCtx) {
        if (traceStore == null || !enableTrace) {
            return new long[]{0, 0};
        }
        return new long[]{
                traceStore.countTools(execCtx.getSessionId()),
                traceStore.countToolFailures(execCtx.getSessionId())};
    }

    /**
     * 写暂停快照（HITL 与 USER_INTERRUPT 共用），失败仅记录日志。
     */
    public void persistPauseState(PauseState state) {
        if (stateStore == null || state == null) {
            return;
        }
        try {
            stateStore.save(state);
        } catch (Exception e) {
            log.warn("[SessionPersister] Failed to persist pause state: {}", e.getMessage());
        }
    }

    /**
     * 恢复成功后删除暂停快照，无配置或已删除返回 false。
     */
    public boolean deletePauseState(String conversationId) {
        if (stateStore == null || conversationId == null) {
            return false;
        }
        stateStore.delete(conversationId);
        return true;
    }
}
