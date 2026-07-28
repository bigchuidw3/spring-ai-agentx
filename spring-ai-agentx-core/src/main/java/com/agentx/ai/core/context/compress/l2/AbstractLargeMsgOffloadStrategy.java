package com.agentx.ai.core.context.compress.l2;

import com.agentx.ai.core.context.ContextPolicy;
import com.agentx.ai.core.context.TokenEstimator;
import com.agentx.ai.core.context.compress.CompressionContext;
import com.agentx.ai.core.context.compress.CompressionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 大消息 offload 策略基类（L2/L3 共享逻辑）。
 * 子类只需实现 {@link #scanEnd(CompressionContext)} 决定扫描上界。
 *
 * @author bigchui
 */
public abstract class AbstractLargeMsgOffloadStrategy implements CompressionStrategy {

    private static final Logger log = LoggerFactory.getLogger(AbstractLargeMsgOffloadStrategy.class);

    /** 历史轮次扫描下界（inclusive），子类可覆盖。 */
    protected int scanStart(CompressionContext ctx) {
        return 0;
    }

    /** 历史轮次扫描上界（exclusive），子类决定是否受 lastKeep 保护。 */
    protected abstract int scanEnd(CompressionContext ctx);

    @Override
    public boolean tryCompress(CompressionContext ctx) {
        ContextPolicy policy = ctx.policy();
        int from = scanStart(ctx);
        int to = scanEnd(ctx);
        if (to <= from) {
            return false;
        }

        var messages = ctx.messages();
        int compressedCount = 0;

        for (int i = from; i < to; i++) {
            Message original = messages.get(i);
            if (!shouldOffload(original, policy.largePayloadTokens())) {
                continue;
            }

            String uuid = ctx.hasOffloadStore()
                    ? ctx.offloadStore().offload(ctx.conversationId(), ctx.sessionId(), original)
                    : null;

            Message replaced = replaceWithPreview(original, uuid, policy);
            messages.set(i, replaced);
            compressedCount++;

            log.info("[{}] Offloaded large message: index={}, originalTokens={}, uuid={}",
                    name(), i, TokenEstimator.estimateTokens(original), uuid);
        }
        return compressedCount > 0;
    }

    private boolean shouldOffload(Message msg, int thresholdTokens) {
        if (msg instanceof AssistantMessage am
                && am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
            return false;
        }
        return TokenEstimator.estimateTokens(msg) > thresholdTokens;
    }

    /**
     * 用预览 + offload 引用替换原消息，保留原消息的 role 与结构。
     * - ToolResponseMessage：保留 id/name，重建 ToolResponseMessage（仅替换 responseData）
     * - AssistantMessage 纯文本：重建 AssistantMessage
     * - UserMessage：重建 UserMessage
     */
    private Message replaceWithPreview(Message original, String uuid, ContextPolicy policy) {
        String fullText = extractText(original);
        int keep = policy.offloadPreviewChars();
        String preview;
        if (fullText.length() <= keep) {
            preview = fullText;
        } else {
            preview = fullText.substring(0, keep)
                    + "...[内容已 offload"
                    + (uuid != null ? ", uuid=" + uuid : "")
                    + "，可调用 context_reload 工具取回]";
        }

        if (original instanceof ToolResponseMessage trm) {
            List<ToolResponseMessage.ToolResponse> rebuilt = new ArrayList<>();
            for (ToolResponseMessage.ToolResponse resp : trm.getResponses()) {
                rebuilt.add(new ToolResponseMessage.ToolResponse(
                        resp.id(), resp.name(), preview));
            }
            return ToolResponseMessage.builder().responses(rebuilt).build();
        }
        if (original instanceof AssistantMessage) {
            return new AssistantMessage(preview);
        }
        return new UserMessage(preview);
    }

    private String extractText(Message msg) {
        if (msg instanceof AssistantMessage am) {
            return am.getText() != null ? am.getText() : "";
        }
        if (msg instanceof ToolResponseMessage trm) {
            StringBuilder sb = new StringBuilder();
            for (ToolResponseMessage.ToolResponse resp : trm.getResponses()) {
                if (resp.responseData() != null) {
                    sb.append(resp.responseData());
                }
            }
            return sb.toString();
        }
        String text = msg.getText();
        return text != null ? text : "";
    }
}
