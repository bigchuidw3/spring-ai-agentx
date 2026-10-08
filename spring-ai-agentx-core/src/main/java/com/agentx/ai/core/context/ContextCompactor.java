package com.agentx.ai.core.context;

import com.agentx.ai.core.context.compress.CompactionTracer;
import com.agentx.ai.core.context.compress.CompressionContext;
import com.agentx.ai.core.context.compress.CompressionStrategy;
import com.agentx.ai.core.context.compress.LlmSummarizer;
import com.agentx.ai.core.context.compress.OffloadStore;
import com.agentx.ai.core.memory.store.SessionMessageStore;
import com.agentx.ai.core.trace.TraceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文压缩器（v1.0.2 重构为责任链入口）。
 * 每轮 LLM 调用前由 AgentLoopExecutor 调用，按策略链顺序尝试压缩。
 * 第一个返回 true 的策略终止链，压缩结果同步覆盖到 working_messages。
 *
 * @author bigchui
 */
public class ContextCompactor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactor.class);

    private final ContextPolicy policy;
    private final ChatModel chatModel;
    private final OffloadStore offloadStore;
    private final SessionMessageStore sessionMessageStore;
    private final List<CompressionStrategy> strategies;
    private final LlmSummarizer summarizer;
    private final CompactionTracer tracer = new CompactionTracer();

    public ContextCompactor(ContextPolicy policy, ChatModel chatModel,
                            OffloadStore offloadStore, SessionMessageStore sessionMessageStore,
                            List<CompressionStrategy> strategies, LlmSummarizer summarizer) {
        this.policy = policy;
        this.chatModel = chatModel;
        this.offloadStore = offloadStore;
        this.sessionMessageStore = sessionMessageStore;
        this.strategies = strategies != null ? strategies : new ArrayList<>();
        this.summarizer = summarizer;
    }

    /**
     * 主入口：执行策略链。任一策略返回 true 则终止并持久化 working_messages。
     */
    public void compact(List<Message> messages, String query,
                        String conversationId, long sessionId, String userId,
                        int round, TraceManager traceManager) {
        if (messages == null || messages.size() <= 2) {
            return;
        }
        if (strategies.isEmpty()) {
            return;
        }
        if (!outerGatePassed(messages)) {
            return;
        }

        int beforeTokens = TokenEstimator.estimateTokens(messages);
        int beforeMessages = messages.size();

        CompressionContext ctx = new CompressionContext(
                messages, query, conversationId, sessionId, userId,
                policy, chatModel, offloadStore);

        for (CompressionStrategy strategy : strategies) {
            try {
                if (strategy.tryCompress(ctx)) {
                    int afterTokens = TokenEstimator.estimateTokens(messages);
                    int afterMessages = messages.size();
                    log.info("[ContextCompactor] {} triggered compression: messages={} -> {}, tokens={} -> {}",
                            strategy.name(), beforeMessages, afterMessages, beforeTokens, afterTokens);
                    persistWorkingMessages(conversationId, sessionId, userId, messages);
                    tracer.record(traceManager, round, strategy.name(),
                            beforeTokens, afterTokens, beforeMessages, afterMessages,
                            ctx.getSummaryPromptTokens(), ctx.getSummaryCompletionTokens(), summarizer);
                    return;
                }
            } catch (Exception e) {
                log.warn("[ContextCompactor] {} failed, continuing chain: {}",
                        strategy.name(), e.getMessage());
            }
        }
    }

    /**
     * 兼容入口：未提供 conversationId 时不持久化。
     */
    public void compact(List<Message> messages, String query) {
        compact(messages, query, null, 0L, null, 0, null);
    }

    public void compact(List<Message> messages) {
        compact(messages, null, null, 0L, null, 0, null);
    }

    /**
     * 外层门禁：消息数或 token 超限才进入策略链。
     * 对齐 AgentScope：使用独立 msgThreshold + tokenThreshold；lastKeep 只负责保护尾部，不参与触发。
     */
    private boolean outerGatePassed(List<Message> messages) {
        if (messages.size() >= policy.msgThreshold()) {
            return true;
        }
        int estimatedTokens = TokenEstimator.estimateTokens(messages);
        return estimatedTokens >= policy.tokenThreshold();
    }

    /**
     * 压缩发生时把当前 messages 视图覆盖写到 working_messages。
     */
    private void persistWorkingMessages(String conversationId, long sessionId, String userId, List<Message> messages) {
        if (conversationId == null || sessionMessageStore == null) {
            return;
        }
        try {
            sessionMessageStore.replaceMessages(
                    conversationId, sessionId, userId, "working_messages", messages);
        } catch (Exception e) {
            log.warn("[ContextCompactor] Failed to persist working_messages: {}", e.getMessage());
        }
    }

    public List<CompressionStrategy> strategies() {
        return strategies;
    }
}
