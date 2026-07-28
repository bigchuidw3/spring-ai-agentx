package com.agentx.ai.core.context.compress;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 被压缩消息的原文存储接口。
 * 被替换出的消息段按一个 UUID 整体存储，供 context_reload 工具回溯。
 *
 * @author bigchui
 */
public interface OffloadStore {

    /**
     * 将一段原始消息 offload，返回分配的 UUID。
     * conversationId 为 null 时直接返回 null（不持久化）。
     */
    String offload(String conversationId, long sessionId, List<Message> messages);

    default String offload(String conversationId, long sessionId, Message message) {
        return message == null ? null : offload(conversationId, sessionId, List.of(message));
    }

    /**
     * 按 UUID 取回原文消息段。不存在或不可用时返回空列表。
     */
    List<Message> load(String conversationId, String uuid);
}
