package com.agentx.ai.core.memory.extractor;

import com.agentx.ai.core.prompt.PromptConstants;
import com.agentx.ai.core.stage.ThinkTagParser;
import com.agentx.ai.core.memory.model.MemoryItem;
import com.agentx.ai.core.memory.model.MemoryType;
import com.agentx.ai.core.memory.store.MemoryStore;
import com.agentx.ai.core.utils.JsonRepairUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 驱动的记忆提取器。
 *
 * 在对话结束后，使用 LLM 分析对话内容，自动提取值得长期记住的信息，
 * 并按 PROFILE/PREFERENCE/INSTRUCTION/FACT 分类后保存到 MemoryStore。
 *
 * 保存时会与已有记忆合并：用 LLM 解决冲突（如用户改变了职业、偏好等），
 * 去除重复，保留最新信息。
 *
 * @author bigchui
 * 
 */
public class MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);

    /** 结构化输出 DTO，用于 BeanOutputConverter 生成格式指令 */
    private record ExtractionItem(String type, String content, String description) {}

    private final ChatModel chatModel;
    private final MemoryStore memoryStore;
    private final ChatClient chatClient;
    private final BeanOutputConverter<List<ExtractionItem>> converter;

    public MemoryExtractor(ChatModel chatModel, MemoryStore memoryStore) {
        this.chatModel = chatModel;
        this.memoryStore = memoryStore;
        this.chatClient = ChatClient.builder(chatModel).build();
        this.converter = new BeanOutputConverter<>(new ParameterizedTypeReference<>() {});
    }

    /**
     * 从问答对中提取记忆并保存（自动与已有记忆合并）。
     *
     * @param userId   用户标识
     * @param question 用户问题
     * @param answer   助手回答
     */
    public void extractAndSave(String userId, String question, String answer) {
        if (userId == null || question == null || answer == null) {
            return;
        }

        // 1. 从本次对话提取新记忆
        List<MemoryItem> newMemories = extract(userId, question, answer);
        if (newMemories.isEmpty()) {
            log.debug("No new memories extracted for userId={}", userId);
            return;
        }

        // 2. 加载已有记忆
        List<MemoryItem> existingMemories = memoryStore.findByUserId(userId);

        // 3. 合并
        List<MemoryItem> consolidated;
        if (existingMemories.isEmpty()) {
            consolidated = newMemories;
            log.debug("No existing memories, saving {} new memories for userId={}", newMemories.size(), userId);
        } else {
            consolidated = consolidate(userId, existingMemories, newMemories);
            log.debug("Consolidated {} existing + {} new → {} memories for userId={}",
                    existingMemories.size(), newMemories.size(), consolidated.size(), userId);
        }

        // 4. 删除旧记忆，保存合并后的结果
        memoryStore.deleteByUserId(userId);
        for (MemoryItem item : consolidated) {
            memoryStore.save(userId, item);
        }

        logMemorySummary(userId, consolidated);
    }

    /**
     * 从问答对中提取记忆（不保存）。
     */
    List<MemoryItem> extract(String userId, String question, String answer) {
        String conversationText = formatConversation(question, answer);
        if (conversationText.isBlank()) {
            return List.of();
        }

        try {
            String systemPrompt = PromptConstants.MEMORY_EXTRACTION_PROMPT + "\n\n" + converter.getFormat();
            String userPrompt = "对话内容：\n" + conversationText;
            List<ExtractionItem> items = callStructuredLlm(systemPrompt, userPrompt);
            return parseExtractionResult(userId, items);
        } catch (Exception e) {
            log.error("Memory extraction failed for userId={}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 用 LLM 合并新旧记忆：去重、解决冲突。
     */
    private List<MemoryItem> consolidate(String userId, List<MemoryItem> existing, List<MemoryItem> newMemories) {
        try {
            String existingText = formatMemoriesForConsolidation(existing, "已有记忆");
            String newText = formatMemoriesForConsolidation(newMemories, "新提取的记忆");

            String systemPrompt = PromptConstants.MEMORY_CONSOLIDATION_PROMPT + "\n\n" + converter.getFormat();
            String userPrompt = existingText + "\n\n" + newText;
            List<ExtractionItem> items = callStructuredLlm(systemPrompt, userPrompt);
            return parseExtractionResult(userId, items);
        } catch (Exception e) {
            log.error("Memory consolidation failed for userId={}, falling back to merge: {}", userId, e.getMessage());
            // 降级：合并去重但不做智能冲突处理
            List<MemoryItem> merged = new ArrayList<>(existing);
            merged.addAll(newMemories);
            return merged;
        }
    }

    // ==================== 通用 LLM 结构化调用 ====================

    /**
     * 通用结构化 LLM 调用：发送请求 → 剥离 think → 修复 JSON → 转换为结构化对象。
     */
    private List<ExtractionItem> callStructuredLlm(String systemPrompt, String userPrompt) {
        String result = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt + "\n<no_think>")
                .call()
                .content();
        String fixed = JsonRepairUtil.fixJson(ThinkTagParser.stripThinkTags(result));
        return converter.convert(fixed);
    }

    // ==================== 工具方法 ====================

    private String formatConversation(String question, String answer) {
        return "用户: " + question + "\n助手: " + answer + "\n";
    }

    private String formatMemoriesForConsolidation(List<MemoryItem> memories, String label) {
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(label).append("】\n");
        sb.append("[");
        for (int i = 0; i < memories.size(); i++) {
            MemoryItem m = memories.get(i);
            if (i > 0) sb.append(",");
            sb.append("\n  {\"type\": \"").append(m.getType().name())
                    .append("\", \"content\": \"").append(escapeJson(m.getContent()))
                    .append("\", \"description\": \"").append(escapeJson(m.getDescription()))
                    .append("\"}");
        }
        sb.append("\n]");
        return sb.toString();
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private List<MemoryItem> parseExtractionResult(String userId, List<ExtractionItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        List<MemoryItem> result = new ArrayList<>();
        for (ExtractionItem item : items) {
            if (item.type() == null || item.content() == null) {
                continue;
            }
            try {
                MemoryType type = MemoryType.valueOf(item.type().toUpperCase());
                result.add(MemoryItem.builder()
                        .userId(userId)
                        .type(type)
                        .content(item.content())
                        .description(item.description() != null ? item.description() : item.content())
                        .build());
            } catch (IllegalArgumentException e) {
                log.warn("Unknown memory type: {}, skipping", item.type());
            }
        }
        return result;
    }

    private void logMemorySummary(String userId, List<MemoryItem> memories) {
        log.info("Memory summary for userId={}, total={}:", userId, memories.size());
        for (MemoryItem m : memories) {
            log.info("  [{}] {}",
                    m.getType().name(),
                    m.getContent().length() > 80
                            ? m.getContent().substring(0, 80) + "..."
                            : m.getContent());
        }
    }
}
