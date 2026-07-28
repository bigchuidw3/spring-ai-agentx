package com.agentx.ai.core.context.compress;

import com.agentx.ai.core.context.ContextPolicy;
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
    private final ContextPolicy policy;
    private final ChatModel chatModel;
    private final OffloadStore offloadStore;

    public CompressionContext(List<Message> messages, String query,
                              String conversationId, long sessionId,
                              ContextPolicy policy, ChatModel chatModel,
                              OffloadStore offloadStore) {
        this.messages = messages;
        this.query = query;
        this.conversationId = conversationId;
        this.sessionId = sessionId;
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
}
