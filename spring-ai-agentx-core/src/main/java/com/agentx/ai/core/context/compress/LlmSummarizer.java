package com.agentx.ai.core.context.compress;

import com.agentx.ai.core.stage.ThinkTagParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

/**
 * LLM 摘要调用封装（L4/L5/L6 共享）。
 * 统一处理 think 标签剥离与失败回退。
 *
 * @author bigchui
 */
public class LlmSummarizer {

    private static final Logger log = LoggerFactory.getLogger(LlmSummarizer.class);

    private final ChatModel chatModel;

    public LlmSummarizer(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 调用 LLM 生成摘要。
     *
     * @param systemPrompt 系统提示词（定义摘要风格与约束）
     * @param userPrompt   用户提示词（含待压缩原文）
     * @return 摘要文本；失败返回 null
     */
    public String summarize(String systemPrompt, String userPrompt) {
        try {
            ChatResponse response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt)
            )));
            String summary = response.getResult().getOutput().getText();
            summary = ThinkTagParser.stripThinkTags(summary);
            log.debug("[LlmSummarizer] summary generated: inputChars={}, outputChars={}",
                    userPrompt != null ? userPrompt.length() : 0,
                    summary != null ? summary.length() : 0);
            return summary;
        } catch (Exception e) {
            log.warn("[LlmSummarizer] LLM call failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将消息列表序列化为 LLM 可读的对话文本。
     */
    public static String buildConversationText(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            sb.append("[").append(msg.getMessageType()).append("] ");
            sb.append(extractText(msg));
            sb.append("\n\n");
        }
        return sb.toString();
    }

    private static String extractText(Message msg) {
        if (msg instanceof org.springframework.ai.chat.messages.AssistantMessage am) {
            StringBuilder sb = new StringBuilder();
            if (am.getText() != null) {
                sb.append(am.getText());
            }
            if (am.getToolCalls() != null) {
                for (var tc : am.getToolCalls()) {
                    sb.append("\n[ToolCall: ").append(tc.name())
                      .append(" args=").append(tc.arguments()).append("]");
                }
            }
            return sb.toString();
        }
        if (msg instanceof org.springframework.ai.chat.messages.ToolResponseMessage trm) {
            StringBuilder sb = new StringBuilder();
            for (var resp : trm.getResponses()) {
                if (resp.responseData() != null) {
                    sb.append(resp.responseData());
                }
            }
            return sb.toString();
        }
        return msg.getText() != null ? msg.getText() : "";
    }
}
