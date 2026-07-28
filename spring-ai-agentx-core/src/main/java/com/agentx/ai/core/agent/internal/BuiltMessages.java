package com.agentx.ai.core.agent.internal;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * buildInitialMessages 的返回值：消息列表 + 本次调用新增消息的起点。
 *
 * @author bigchui
 */
public record BuiltMessages(List<Message> messages, int newMsgStartIndex) {
}
