package com.agentx.ai.core.model;

import org.springframework.ai.chat.messages.Message;

import java.time.LocalDateTime;

/**
 * 消息与其生成时刻的绑定，用于终态批量落库时携带每条消息自己的创建时间。
 *
 * @param message   消息
 * @param createdAt 消息生成时刻
 */
public record TimedMessage(Message message, LocalDateTime createdAt) {
}
