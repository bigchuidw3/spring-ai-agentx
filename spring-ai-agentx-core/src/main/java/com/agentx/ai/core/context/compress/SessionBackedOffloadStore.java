package com.agentx.ai.core.context.compress;

import com.agentx.ai.core.memory.store.SessionMessageStore;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.UUID;

/**
 * 基于 SessionMessageStore 的 offload 实现，把原文写入 agentx_session.offload_context。
 *
 * @author bigchui
 */
public class SessionBackedOffloadStore implements OffloadStore {

    private final SessionMessageStore sessionMessageStore;

    public SessionBackedOffloadStore(SessionMessageStore sessionMessageStore) {
        this.sessionMessageStore = sessionMessageStore;
    }

    @Override
    public String offload(String conversationId, long sessionId, List<Message> messages) {
        if (conversationId == null || messages == null || messages.isEmpty() || sessionMessageStore == null) {
            return null;
        }
        String uuid = UUID.randomUUID().toString();
        sessionMessageStore.appendOffloadMessages(conversationId, sessionId, uuid, messages);
        return uuid;
    }

    @Override
    public List<Message> load(String conversationId, String uuid) {
        if (conversationId == null || uuid == null || sessionMessageStore == null) {
            return List.of();
        }
        return sessionMessageStore.getOffloaded(conversationId, uuid);
    }
}
