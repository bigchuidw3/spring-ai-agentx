package com.agentx.ai.core.context.compress.l6;

import com.agentx.ai.core.context.TokenEstimator;
import com.agentx.ai.core.context.compress.CompressionContext;
import com.agentx.ai.core.context.compress.CompressionStrategy;
import com.agentx.ai.core.context.compress.LlmSummarizer;
import com.agentx.ai.core.prompt.PromptConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * L6 当前任务整体 LLM 压缩。
 * 保留最近一条 user 原话，仅压缩该 user 之后的当前轮消息。
 *
 * @author bigchui
 */
public class CurrentRoundOverallStrategy implements CompressionStrategy {

    private static final Logger log = LoggerFactory.getLogger(CurrentRoundOverallStrategy.class);

    private final LlmSummarizer summarizer;

    public CurrentRoundOverallStrategy(LlmSummarizer summarizer) {
        this.summarizer = summarizer;
    }

    @Override
    public boolean tryCompress(CompressionContext ctx) {
        int userIdx = ctx.latestUserMsgIndex();
        if (userIdx < 0 || userIdx >= ctx.messages().size() - 1) {
            return false;
        }

        var messages = ctx.messages();
        int start = userIdx + 1;
        List<Message> currentRound = new ArrayList<>(messages.subList(start, messages.size()));
        int totalTokens = TokenEstimator.estimateTokens(currentRound);
        int targetTokens = (int) Math.round(totalTokens * ctx.policy().currentRoundRatio());

        if (targetTokens >= totalTokens) {
            return false;
        }

        String conversationText = LlmSummarizer.buildConversationText(currentRound);
        String tokenRequirement = String.format(
                "（原 token 数：%d，目标 token 数：%d，压缩比：%.0f%%）%n",
                totalTokens, targetTokens, ctx.policy().currentRoundRatio() * 100);

        String summary = summarizer.summarize(
                PromptConstants.CONTEXT_COMPACT_SUMMARY_SYSTEM_PROMPT,
                PromptConstants.buildCompactSummaryUserPrompt(
                        conversationText + "\n\n## 压缩要求\n" + tokenRequirement,
                        ctx.query()));
        if (summary == null || summary.isBlank()) {
            log.info("[L6] LLM summary empty, skipping");
            return false;
        }

        String uuid = ctx.hasOffloadStore()
                ? ctx.offloadStore().offload(ctx.conversationId(), ctx.sessionId(), currentRound)
                : null;
        String summaryMessage = "[当前任务压缩摘要]\n" + summary + buildOffloadHint(uuid);
        for (int i = messages.size() - 1; i >= start; i--) {
            messages.remove(i);
        }
        messages.add(start, new AssistantMessage(summaryMessage));

        log.info("[L6] Compressed current round: {} messages → 1 summary, inputTokens={}, targetTokens={}, actualTokens={}",
                currentRound.size(), totalTokens, targetTokens, TokenEstimator.estimateTokens(summary));
        return true;
    }

    private String buildOffloadHint(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return "\n\n（当前未启用 offload 存储，仅保留摘要）";
        }
        return "\n\n（该段完整原文已 offload，uuid=" + uuid
                + "；如需原文可调用 context_reload(uuid) 工具取回）";
    }

    @Override
    public String name() {
        return "L6-CurrentRoundOverall";
    }
}
