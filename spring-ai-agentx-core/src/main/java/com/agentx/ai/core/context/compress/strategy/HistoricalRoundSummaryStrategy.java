package com.agentx.ai.core.context.compress.strategy;

import com.agentx.ai.core.context.TokenEstimator;
import com.agentx.ai.core.context.compress.CompressionContext;
import com.agentx.ai.core.context.compress.CompressionStrategy;
import com.agentx.ai.core.context.compress.LlmSummarizer;
import com.agentx.ai.core.prompt.PromptConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * L4 历史轮次 LLM 摘要。
 * 在 L1-L3 都未触发时，按 user→assistant 轮次分段压缩历史消息，保留每条 user 原话。
 *
 * @author bigchui
 */
public class HistoricalRoundSummaryStrategy implements CompressionStrategy {

    private static final Logger log = LoggerFactory.getLogger(HistoricalRoundSummaryStrategy.class);

    private final LlmSummarizer summarizer;

    public HistoricalRoundSummaryStrategy(LlmSummarizer summarizer) {
        this.summarizer = summarizer;
    }

    @Override
    public boolean tryCompress(CompressionContext ctx) {
        int scanEnd = ctx.historicalScanEnd(ctx.policy().lastKeep());
        if (scanEnd <= 0) {
            return false;
        }

        var messages = ctx.messages();
        List<int[]> userAssistantPairs = findUserAssistantPairs(messages, scanEnd);
        if (userAssistantPairs.isEmpty()) {
            return false;
        }

        boolean compressed = false;
        int compressedRounds = 0;
        int maxCount = ctx.policy().maxLlmCompressionCount();

        for (int pairIdx = userAssistantPairs.size() - 1; pairIdx >= 0; pairIdx--) {
            if (compressedRounds >= maxCount) {
                break;
            }
            int userIdx = userAssistantPairs.get(pairIdx)[0];
            int assistantIdx = userAssistantPairs.get(pairIdx)[1];
            int start = userIdx + 1;
            int endExclusive = assistantIdx + 1;
            if (start >= endExclusive) {
                continue;
            }

            List<Message> segment = new ArrayList<>();
            segment.add(messages.get(userIdx));
            segment.addAll(messages.subList(start, endExclusive));
            int totalTokens = TokenEstimator.estimateTokens(segment);
            if (totalTokens < ctx.policy().minCompressionTokens()) {
                continue;
            }

            String conversationText = LlmSummarizer.buildConversationText(segment);
            String summary = summarizer.summarize(
                    PromptConstants.SESSION_SUMMARIZATION_PROMPT,
                    buildUserPrompt(conversationText, ctx.query()));
            if (summary == null || summary.isBlank()) {
                log.info("[L4] LLM summary returned empty for round={}, skipping", pairIdx + 1);
                continue;
            }

            String uuid = ctx.hasOffloadStore()
                    ? ctx.offloadStore().offload(ctx.conversationId(), ctx.sessionId(), segment)
                    : null;
            String summaryMessage = "[历史轮次摘要]\n" + summary + buildOffloadHint(uuid);
            for (int i = endExclusive - 1; i >= start; i--) {
                messages.remove(i);
            }
            messages.add(start, new AssistantMessage(summaryMessage));

            compressed = true;
            compressedRounds++;
        }

        if (compressed) {
            log.info("[L4] Summarized historical rounds: rounds={}, scanEnd={}", compressedRounds, scanEnd);
        }
        return compressed;
    }

    private List<int[]> findUserAssistantPairs(List<Message> messages, int upperBound) {
        List<int[]> pairs = new ArrayList<>();
        int currentUserIndex = -1;
        for (int i = 0; i < upperBound; i++) {
            Message msg = messages.get(i);
            if (msg instanceof SystemMessage) {
                continue;
            }
            if (msg instanceof UserMessage) {
                currentUserIndex = i;
                continue;
            }
            if (isFinalAssistantResponse(msg) && currentUserIndex >= 0 && i - currentUserIndex > 1) {
                pairs.add(new int[]{currentUserIndex, i});
                currentUserIndex = -1;
            }
        }
        return pairs;
    }

    private boolean isFinalAssistantResponse(Message msg) {
        if (!(msg instanceof AssistantMessage am)) {
            return false;
        }
        return am.getToolCalls() == null || am.getToolCalls().isEmpty();
    }

    private String buildUserPrompt(String conversationText, String currentQuery) {
        StringBuilder sb = new StringBuilder();
        sb.append("请将以下历史对话压缩为长期会话摘要");
        if (currentQuery != null && !currentQuery.isBlank()) {
            sb.append("，当前用户请求为：").append(currentQuery);
        }
        sb.append("。\n\n## 对话记录\n").append(conversationText);
        sb.append("\n<no_think>");
        return sb.toString();
    }

    private String buildOffloadHint(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return "\n\n（当前未启用 offload 存储，仅保留摘要）";
        }
        return "\n\n（该段完整历史已 offload，uuid=" + uuid
                + "；如需原文可调用 context_reload(uuid) 工具取回）";
    }

    @Override
    public String name() {
        return "L4-HistoricalRoundSummary";
    }
}
