package com.agentx.ai.core.memory;

import com.agentx.ai.core.prompt.PromptConstants;
import com.agentx.ai.core.stage.ThinkTagParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.ParameterizedTypeReference;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 长期记忆管理器。
 *
 * <p>封装跨会话记忆的「抽取 - 去重 - 合并 - 检索」全流程，所有数据落在
 * 单一 doc type {@value #DOC_TYPE} 的 PgVectorStore 表中。
 *
 * <h3>写入路径</h3>
 * <ol>
 *   <li>LLM 调用 #1：从本次调用 transcript 抽取候选记忆（JSON 数组）</li>
 *   <li>对每条候选：embedding 检索 top-K 相似记忆</li>
 *   <li>命中 → LLM 调用 #2 合并旧 + 新 → delete 旧 + insert 合并</li>
 *   <li>未命中 → 直接 insert</li>
 * </ol>
 *
 * <h3>读取路径</h3>
 * 按 userId + query 语义检索 top-K 相关记忆，注入 SystemMessage。
 *
 * @author bigchui
 */
public class LongTermMemoryManager {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryManager.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static final String DOC_TYPE = "memory";
    public static final String META_TYPE = "type";
    public static final String META_USER_ID = "user_id";
    public static final String META_CONVERSATION_ID = "conversation_id";
    public static final String META_CREATED_AT = "created_at";

    private final VectorStore vectorStore;
    private final ChatModel chatModel;
    private final LongTermMemoryConfig config;

    public LongTermMemoryManager(LongTermMemoryConfig config, ChatModel chatModel) {
        this.config = config;
        this.chatModel = chatModel;
        this.vectorStore = config.getVectorStore();
    }

    // ==================== 写入：抽取 + 去重/合并 ====================

    /**
     * 处理一次完整 ReAct 调用产生的消息链：抽取候选 → 检索去重 → 合并或新增。
     */
    public void ingestTranscript(String userId, String conversationId, List<Message> transcript) {
        if (userId == null || transcript == null || transcript.isEmpty()) {
            return;
        }
        try {
            List<String> candidates = extractCandidates(transcript);
            if (candidates.isEmpty()) {
                log.debug("No memory candidates extracted: userId={}, conversationId={}", userId, conversationId);
                return;
            }
            int upserted = 0;
            for (String candidate : candidates) {
                if (upsertMemory(userId, conversationId, candidate)) {
                    upserted++;
                }
            }
            log.info("Ingested memories: userId={}, conversationId={}, candidates={}, written={}",
                    userId, conversationId, candidates.size(), upserted);
        } catch (Exception e) {
            log.error("Memory ingestion failed: userId={}, conversationId={}, err={}",
                    userId, conversationId, e.getMessage(), e);
        }
    }

    /**
     * 单条候选记忆的去重/合并入库。
     *
     * @return true 表示已写入（新增或合并），false 表示跳过
     */
    private boolean upsertMemory(String userId, String conversationId, String candidate) {
        List<Document> similar = searchSimilar(userId, candidate);
        if (similar.isEmpty()) {
            insertMemory(userId, conversationId, candidate);
            log.debug("Inserted new memory: userId={}, preview={}", userId, preview(candidate));
            return true;
        }
        String merged = mergeMemories(similar, candidate);
        if (merged == null || merged.isBlank()) {
            log.debug("Merge produced empty result, skipping");
            return false;
        }
        List<String> idsToDelete = similar.stream().map(Document::getId).toList();
        vectorStore.delete(idsToDelete);
        insertMemory(userId, conversationId, merged);
        log.debug("Merged memory: userId={}, replaced={} docs, preview={}",
                userId, idsToDelete.size(), preview(merged));
        return true;
    }

    private String extractPrompt() {
        String p = config.getExtractPrompt();
        return p != null ? p : PromptConstants.MEMORY_EXTRACT_PROMPT;
    }

    private String mergePrompt() {
        String p = config.getMergePrompt();
        return p != null ? p : PromptConstants.MEMORY_MERGE_PROMPT;
    }

    private List<String> extractCandidates(List<Message> transcript) {
        String transcriptText = formatTranscript(transcript);
        if (transcriptText.isBlank()) {
            return List.of();
        }
        try {
            var typeRef = new ParameterizedTypeReference<List<String>>() {};
            var converter = new BeanOutputConverter<>(typeRef);
            List<String> raw = ChatClient.builder(chatModel)
                    .build()
                    .prompt()
                    .system(extractPrompt())
                    .user(transcriptText + "\n<no_think>\n" + converter.getFormat())
                    .call()
                    .entity(typeRef);
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<String> filtered = new ArrayList<>(raw.size());
            for (String s : raw) {
                if (s != null && !s.isBlank()) {
                    filtered.add(s.trim());
                    if (filtered.size() >= config.getMaxCandidatesPerCall()) {
                        break;
                    }
                }
            }
            return filtered;
        } catch (Exception e) {
            log.warn("Memory extraction failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String mergeMemories(List<Document> existing, String candidate) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 新记忆\n").append(candidate).append("\n\n");
        sb.append("## 已有相似记忆\n");
        for (int i = 0; i < existing.size(); i++) {
            sb.append("### ").append(i + 1).append("\n")
                    .append(existing.get(i).getText()).append("\n\n");
        }
        try {
            String result = ChatClient.builder(chatModel)
                    .build()
                    .prompt()
                    .system(mergePrompt())
                    .user(sb.toString() + "\n<no_think>")
                    .call()
                    .content();
            return result == null ? null : ThinkTagParser.stripThinkTags(result.trim());
        } catch (Exception e) {
            log.warn("Memory merge failed, falling back to new candidate: {}", e.getMessage());
            return candidate;
        }
    }

    private void insertMemory(String userId, String conversationId, String text) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(META_TYPE, DOC_TYPE);
        metadata.put(META_USER_ID, userId);
        metadata.put(META_CONVERSATION_ID, conversationId != null ? conversationId : "");
        metadata.put(META_CREATED_AT, LocalDateTime.now().format(DATE_FORMATTER));
        Document doc = Document.builder()
                .id(UUID.randomUUID().toString())
                .text(text)
                .metadata(metadata)
                .build();
        vectorStore.add(List.of(doc));
    }

    // ==================== 读取：检索注入 ====================

    /**
     * 按用户和当前 query 检索 top-K 相关记忆，供 SystemMessage 注入。
     */
    public List<Document> searchRelevant(String userId, String query) {
        if (userId == null || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            return vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(config.getTopK())
                    .similarityThreshold(config.getSimilarityThreshold())
                    .filterExpression(userMemoryFilter(userId))
                    .build());
        } catch (Exception e) {
            log.warn("Memory search failed: userId={}, err={}", userId, e.getMessage());
            return List.of();
        }
    }

    private List<Document> searchSimilar(String userId, String candidate) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(candidate)
                .topK(config.getDedupTopK())
                .similarityThreshold(config.getDedupThreshold())
                .filterExpression(userMemoryFilter(userId))
                .build());
    }

    private static org.springframework.ai.vectorstore.filter.Filter.Expression userMemoryFilter(String userId) {
        FilterExpressionBuilder fb = new FilterExpressionBuilder();
        return fb.and(
                fb.eq(META_USER_ID, userId),
                fb.eq(META_TYPE, DOC_TYPE)
        ).build();
    }

    // ==================== 工具方法 ====================

    private static String formatTranscript(List<Message> transcript) {
        StringBuilder sb = new StringBuilder();
        for (Message msg : transcript) {
            String line = formatMessage(msg);
            if (line != null && !line.isBlank()) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private static String formatMessage(Message msg) {
        if (msg instanceof UserMessage um) {
            String text = um.getText();
            return (text == null || text.isBlank()) ? null : "用户：" + text;
        }
        if (msg instanceof AssistantMessage am) {
            String text = am.getText();
            List<AssistantMessage.ToolCall> calls = am.getToolCalls();
            StringBuilder sb = new StringBuilder();
            if (text != null && !text.isBlank()) {
                sb.append("助手：").append(text);
            }
            if (calls != null && !calls.isEmpty()) {
                for (AssistantMessage.ToolCall tc : calls) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append("工具调用：").append(tc.name())
                            .append("(").append(tc.arguments()).append(")");
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        if (msg instanceof ToolResponseMessage trm) {
            StringBuilder sb = new StringBuilder();
            for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
                sb.append("工具结果[").append(tr.name()).append("]：")
                        .append(tr.responseData()).append("\n");
            }
            return sb.length() == 0 ? null : sb.toString().trim();
        }
        return null;
    }

    private static String preview(String text) {
        if (text == null) return "";
        return text.length() > 60 ? text.substring(0, 60) + "..." : text;
    }
}
