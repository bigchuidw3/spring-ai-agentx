package com.agentx.ai.core.context.compress.l5;

import com.agentx.ai.core.context.TokenEstimator;
import com.agentx.ai.core.context.compress.CompressionContext;
import com.agentx.ai.core.context.compress.CompressionStrategy;
import com.agentx.ai.core.context.compress.LlmSummarizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * L5 当前轮大消息 LLM 摘要。
 * 在当前任务区域（latestUserMsgIndex 之后）找单条 > largePayloadTokens 的消息，
 * 交给 LLM 做保守压缩，原文 offload 供回溯。
 *
 * @author bigchui
 */
public class CurrentRoundLargeMsgStrategy implements CompressionStrategy {

    private static final Logger log = LoggerFactory.getLogger(CurrentRoundLargeMsgStrategy.class);

    private static final String SYSTEM_PROMPT = """
            你是消息压缩助手。请将下面这条消息压缩为更短的等价描述，供 LLM 继续执行当前任务时使用。

            ## 压缩原则
            - 保留所有事实性信息：路径、URL、数字、ID、关键字段
            - 保留消息中与"当前任务"相关的部分（如错误信息、关键返回值）
            - 删除冗长的样板输出、调试信息、列表的重复结构
            - 输出纯文本，不要 JSON/markdown 装饰
            - 不要省略到失去可操作性

            ## 输出
            直接输出压缩后的内容，不要任何前后缀说明。
            """;

    private final LlmSummarizer summarizer;

    public CurrentRoundLargeMsgStrategy(LlmSummarizer summarizer) {
        this.summarizer = summarizer;
    }

    @Override
    public boolean tryCompress(CompressionContext ctx) {
        int userIdx = ctx.latestUserMsgIndex();
        if (userIdx < 0) {
            return false;
        }
        var messages = ctx.messages();
        int threshold = ctx.policy().largePayloadTokens();
        int maxCount = ctx.policy().maxLlmCompressionCount();
        int compressedCount = 0;

        for (int i = userIdx + 1; i < messages.size(); i++) {
            if (compressedCount >= maxCount) {
                break;
            }
            Message original = messages.get(i);
            if (original instanceof AssistantMessage am
                    && am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
                continue;
            }
            int originalTokens = TokenEstimator.estimateTokens(original);
            if (originalTokens <= threshold) {
                continue;
            }

            String fullText = extractText(original);
            String summary = summarizer.summarize(SYSTEM_PROMPT,
                    "待压缩消息（约 " + originalTokens + " tokens）：\n\n" + fullText + "\n\n<no_think>");
            if (summary == null || summary.isBlank()) {
                log.info("[L5] LLM summary empty, skipping index={}", i);
                continue;
            }

            String uuid = ctx.hasOffloadStore()
                    ? ctx.offloadStore().offload(ctx.conversationId(), ctx.sessionId(), original)
                    : null;
            String replaced = summary + "\n[原文已 offload"
                    + (uuid != null ? ", uuid=" + uuid : "")
                    + ", 可调用 context_reload 取回]";
            messages.set(i, rebuildMessagePreservingRole(original, replaced));
            compressedCount++;

            log.info("[L5] Compressed current-round large message: index={}, originalTokens={}, summaryChars={}, uuid={}",
                    i, originalTokens, summary.length(), uuid);
        }
        return compressedCount > 0;
    }

    /**
     * 按原消息类型重建，保留 tool_calls→tool 配对结构：
     * - ToolResponseMessage：保留 id/name，仅替换 responseData
     * - AssistantMessage 纯文本：重建 AssistantMessage
     * - UserMessage：重建 UserMessage
     * 含 tool_calls 的 AssistantMessage 在调用前已被跳过，不会进入此方法。
     */
    private Message rebuildMessagePreservingRole(Message original, String newText) {
        if (original instanceof ToolResponseMessage trm) {
            List<ToolResponseMessage.ToolResponse> rebuilt = new ArrayList<>();
            for (ToolResponseMessage.ToolResponse resp : trm.getResponses()) {
                rebuilt.add(new ToolResponseMessage.ToolResponse(
                        resp.id(), resp.name(), newText));
            }
            return ToolResponseMessage.builder().responses(rebuilt).build();
        }
        if (original instanceof AssistantMessage) {
            return new AssistantMessage(newText);
        }
        return new UserMessage(newText);
    }

    private String extractText(Message msg) {
        if (msg instanceof AssistantMessage am) {
            StringBuilder sb = new StringBuilder();
            if (am.getText() != null) sb.append(am.getText());
            if (am.getToolCalls() != null) {
                for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                    sb.append("[ToolCall ").append(tc.name())
                      .append(" args=").append(tc.arguments()).append("]");
                }
            }
            return sb.toString();
        }
        if (msg instanceof ToolResponseMessage trm) {
            StringBuilder sb = new StringBuilder();
            for (ToolResponseMessage.ToolResponse resp : trm.getResponses()) {
                if (resp.responseData() != null) sb.append(resp.responseData());
            }
            return sb.toString();
        }
        return msg.getText() != null ? msg.getText() : "";
    }

    @Override
    public String name() {
        return "L5-CurrentRoundLargeMsg";
    }
}
