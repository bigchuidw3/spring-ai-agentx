package com.agentx.ai.core.context.compress;

import com.agentx.ai.core.context.ContextPolicy;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

/**
 * 单次压缩调用的上下文。每轮 LLM 调用前由 ContextCompactor 构造一次。
 * 策略通过修改 messages 列表产生压缩效果，通过 offloadStore 持久化被替换出的原文。
 *
 * @author bigchui
 */
public class CompressionContext {

    private final List<Message> messages;
    private final String query;
    private final String conversationId;
    private final long sessionId;
    private final String userId;
    private final ContextPolicy policy;
    private final ChatModel chatModel;
    private final OffloadStore offloadStore;

    /** 本轮 compact 中摘要 LLM 调用（L4/L5/L6）的累计 token，供 COMPACT trace 记录摘要调用 */
    private long summaryPromptTokens = 0;
    private long summaryCompletionTokens = 0;

    public CompressionContext(List<Message> messages, String query,
                              String conversationId, long sessionId, String userId,
                              ContextPolicy policy, ChatModel chatModel,
                              OffloadStore offloadStore) {
        this.messages = messages;
        this.query = query;
        this.conversationId = conversationId;
        this.sessionId = sessionId;
        this.userId = userId;
        this.policy = policy;
        this.chatModel = chatModel;
        this.offloadStore = offloadStore;
    }

    public List<Message> messages() {
        return messages;
    }

    public String query() {
        return query;
    }

    public String conversationId() {
        return conversationId;
    }

    public long sessionId() {
        return sessionId;
    }

    public String userId() {
        return userId;
    }

    public ContextPolicy policy() {
        return policy;
    }

    public ChatModel chatModel() {
        return chatModel;
    }

    public OffloadStore offloadStore() {
        return offloadStore;
    }

    public boolean hasOffloadStore() {
        return offloadStore != null && conversationId != null;
    }

    /**
     * 最近一条 UserMessage 的索引，作为历史轮次与当前任务的分界。
     * 没有任何 UserMessage 时返回 -1。
     */
    public int latestUserMsgIndex() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 历史轮次区域的扫描上界（exclusive）。
     * 受 lastKeep 保护：最末 lastKeep 条消息不进入历史扫描区。
     */
    public int historicalScanEnd(int lastKeep) {
        int latestUser = latestUserMsgIndex();
        if (latestUser < 0) {
            return Math.max(0, messages.size() - lastKeep);
        }
        int protectedStart = Math.max(0, messages.size() - lastKeep);
        return Math.min(latestUser, protectedStart);
    }

    /**
     * 待处理工具结果的保护边界（exclusive）：最后一个含 tool_calls 的 AssistantMessage 索引。
     * <p>该索引之后的 ToolResponseMessage 是 LLM 下一轮正在等待的工具结果，不应被 offload；
     * 该索引之前（含当前轮里已经「看过」的超长工具结果）则允许被 offload。
     * 不存在任何 tool_calls 时返回 {@code messages.size()}（即全链可扫）。
     */
    public int latestToolCallBoundary() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m instanceof AssistantMessage am
                    && am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
                return i;
            }
        }
        return messages.size();
    }

    public void accumulateSummaryTokens(long promptTokens, long completionTokens) {
        this.summaryPromptTokens += promptTokens;
        this.summaryCompletionTokens += completionTokens;
    }

    public long getSummaryPromptTokens() {
        return summaryPromptTokens;
    }

    public long getSummaryCompletionTokens() {
        return summaryCompletionTokens;
    }
}
