package com.agentx.ai.core.agent.internal;

import com.agentx.ai.core.interrupt.PauseReason;
import com.agentx.ai.core.interrupt.PauseStateStore;
import com.agentx.ai.core.memory.store.ConversationStore;
import com.agentx.ai.core.memory.store.SessionMessageStore;
import com.agentx.ai.core.memory.util.MemoryPersistor;
import com.agentx.ai.core.model.PauseState;
import com.agentx.ai.core.model.RunnableParams;
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

    public SessionPersister(boolean enableSession, boolean enableTrace,
                            ConversationStore conversationStore,
                            SessionMessageStore sessionMessageStore,
                            TraceStore traceStore,
                            PauseStateStore stateStore,
                            MemoryPersistor memoryPersistor) {
        this.enableSession = enableSession;
        this.enableTrace = enableTrace;
        this.conversationStore = conversationStore;
        this.sessionMessageStore = sessionMessageStore;
        this.traceStore = traceStore;
        this.stateStore = stateStore;
        this.memoryPersistor = memoryPersistor;
    }

    /**
     * 生成 sessionId、写开局记录、按需创建 TraceManager。
     */
    public void initSession(AgentRuntimeContext execCtx, RunnableParams params, String query) {
        long sessionId = IdWorker.getId();
        execCtx.setSessionId(sessionId);

        String conversationId = params != null ? params.getConversationId() : null;
        String userId = params != null ? params.getUserId() : null;

        if (enableSession && conversationStore != null && conversationId != null) {
            conversationStore.saveStart(conversationId, sessionId, userId, query);
        }

        if (traceStore == null || !enableTrace || conversationId == null) return;
        execCtx.setTraceManager(new TraceManager(traceStore, sessionId, conversationId));
    }

    /**
     * 从 PauseState 恢复会话：HITL 复用原 sessionId，USER_INTERRUPT 新建会话。
     */
    public String initSessionFromResume(AgentRuntimeContext execCtx, PauseState state,
                                        @Nullable String resumeQuery) {
        boolean isInterruptResume = state.getReason() == PauseReason.USER_INTERRUPT
                && resumeQuery != null && !resumeQuery.isBlank();
        execCtx.restoreTokens(state.getTotalPromptTokens(), state.getTotalCompletionTokens());

        if (isInterruptResume) {
            long newSessionId = IdWorker.getId();
            execCtx.setSessionId(newSessionId);
            String conversationId = state.getParams() != null ? state.getParams().getConversationId() : null;
            String userId = state.getParams() != null ? state.getParams().getUserId() : null;
            if (enableSession && conversationStore != null && conversationId != null) {
                conversationStore.saveStart(conversationId, newSessionId, userId, resumeQuery);
            }
            if (traceStore != null && enableTrace && conversationId != null) {
                execCtx.setTraceManager(new TraceManager(traceStore, newSessionId, conversationId));
            }
            return resumeQuery;
        }

        execCtx.setSessionId(state.getSessionId());
        if (traceStore != null && enableTrace) {
            RunnableParams params = state.getParams();
            String conversationId = params != null ? params.getConversationId() : null;
            execCtx.setTraceManager(new TraceManager(traceStore, state.getSessionId(), conversationId));
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
            List<Message> originalSnapshot = execCtx.getOriginalMessagesSnapshot();
            if (originalSnapshot != null && start < originalSnapshot.size()) {
                List<Message> thisCallMessages = new ArrayList<>(originalSnapshot.subList(start, originalSnapshot.size()));
                sessionMessageStore.appendMessages(
                        conversationId, execCtx.getSessionId(),
                        "original_messages", thisCallMessages);
            }
            sessionMessageStore.replaceMessages(
                    conversationId, execCtx.getSessionId(),
                    "working_messages", messages);
            if (conversationStore != null) {
                conversationStore.updateStatus(execCtx.getSessionId(), status);
            }
            if ("completed".equals(status) && memoryPersistor != null) {
                memoryPersistor.persist(execCtx.getParams(), execCtx.getQuery(),
                        extractFinalAnswer(messages, execCtx.getNewMsgStartIndex()), conversationId);
            }
        } catch (Exception e) {
            log.error("Failed to persist terminal session: {}", e.getMessage());
        }
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

    /**
     * 取本次新增消息链里最后一个 AssistantMessage 的文本作为最终答案。
     */
    private String extractFinalAnswer(List<Message> messages, int startIdx) {
        String answer = null;
        for (int i = startIdx; i < messages.size(); i++) {
            if (messages.get(i) instanceof AssistantMessage am && am.getText() != null && !am.getText().isEmpty()) {
                answer = am.getText();
            }
        }
        return answer;
    }
}
