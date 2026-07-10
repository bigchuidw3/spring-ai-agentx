package com.agentx.ai.core.memory.semantic;

import com.agentx.ai.core.prompt.PromptConstants;
import com.agentx.ai.core.memory.store.SemanticMemoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 语义记忆管理器。
 *
 * <h3>三类文档及判断逻辑</h3>
 * <ul>
 *   <li><b>qa_pair</b> — 原始 Q&A，永不修改/删除，按 created_at 升序</li>
 *   <li><b>cross_summary</b> — 跨会话摘要，存 {@code latest_qa_created_at} 水位线
 *     <br>触发：created_at &gt; 水位线的 qa_pair ≥ summarizeThreshold 条</li>
 *   <li><b>session_summary</b> — 单会话溢出摘要
 *     <br>触发：会话 qa_pair 数 &gt; maxHistoryRounds 且溢出量是 step 的整数倍</li>
 * </ul>
 *
 * @author bigchui
 *
 */
public class SemanticMemoryManager {

    private static final Logger log = LoggerFactory.getLogger(SemanticMemoryManager.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 摘要生成每批处理的 Q&A 数量
     */
    private static final int SUMMARIZE_BATCH_SIZE = 5;
    /**
     * 每条 Q&A 内容的字符数上限
     */
    private static final int MAX_QA_CONTENT_LENGTH = 2000;
    /**
     * 单次查询 qa_pair 的最大数量
     */
    private static final int MAX_QA_QUERY_LIMIT = 50;

    private final VectorStore vectorStore;
    private final ChatModel chatModel;
    private final int summarizeThreshold;

    public SemanticMemoryManager(SemanticMemoryStore semanticMemoryStore, ChatModel chatModel) {
        this.vectorStore = semanticMemoryStore.getVectorStore();
        this.chatModel = chatModel;
        this.summarizeThreshold = semanticMemoryStore.getSummarizeThreshold();
    }

    // ==================== 公共 API ====================

    /**
     * 保存 Q&A 到 VectorStore（仅写入，不修改）。
     */
    public void saveQaPair(String userId, String question, String answer, String conversationId) {
        String content = "Q: " + question + "\nA: " + answer;
        Document doc = Document.builder()
                .id(UUID.randomUUID().toString())
                .text(content)
                .metadata(buildQaMetadata(userId, conversationId))
                .build();
        vectorStore.add(List.of(doc));
        log.debug("Saved Q&A to VectorStore: userId={}, conversationId={}", userId, conversationId);
    }

    /**
     * 语义检索跨会话全局知识（cross_summary）。
     * <p>
     * 按 userId 过滤，用当前 query 做语义相似度检索，取 topK 条相关摘要。
     * 排除空文本标记文档。
     */
    public List<Document> searchCrossSummary(String userId, String query, int topK) {
        FilterExpressionBuilder fb = new FilterExpressionBuilder();
        var filter = fb.and(
                fb.eq("user_id", userId),
                fb.eq("type", SemanticMemoryStore.DOC_TYPE_CROSS_SUMMARY)
        ).build();

        return vectorStore.similaritySearch(SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .filterExpression(filter)
                        .build()).stream()
                .filter(doc -> doc.getText() != null && !doc.getText().isBlank())
                .limit(topK)
                .toList();
    }

    /**
     * 获取当前会话的溢出摘要（session_summary）。
     * <p>
     * 每个会话最多一条 session_summary，按 conversationId 精确查找，
     * 不需要语义检索。
     */
    public Document getSessionSummary(String conversationId) {
        return findSessionSummary(conversationId);
    }

    /**
     * 跨会话摘要：水位线 = max(cross_summary.latest_qa_created_at)，
     * created_at &gt; 水位线的 qa_pair 达到阈值时触发摘要。
     */
    public void checkAndSummarize(String userId) {
        while (true) {
            List<Document> unprocessedDocs = findUnprocessedQaPairDocs(userId, summarizeThreshold);
            if (unprocessedDocs.size() < summarizeThreshold) {
                log.debug("Cross-summary not ready for userId={}: {}/{} unprocessed",
                        userId, unprocessedDocs.size(), summarizeThreshold);
                break;
            }
            log.info("Cross-summary triggered for userId={}: {} unprocessed",
                    userId, unprocessedDocs.size());
            summarizeQaPairs(userId, unprocessedDocs);
        }
    }

    /**
     * 会话溢出摘要（增量合并）。
     * <p>
     * 每次只取<b>新增</b>溢出 qa_pair（上次已摘要的跳过），与已有 session_summary
     * 文本合并后生成新摘要。这样每批 LLM 调用只处理 1 条旧摘要 + step 条新对话，
     * 上下文有界，不会随溢出量增长。
     */
    public void checkAndSessionSummarize(String userId, String conversationId,
                                         int maxHistoryRounds, int step) {
        if (maxHistoryRounds <= 0 || step <= 0) {
            return;
        }
        try {
            List<Document> qaDocs = findQaPairDocsByConversation(conversationId);
            int overflowCount = qaDocs.size() - maxHistoryRounds;
            if (overflowCount <= 0 || overflowCount % step != 0) {
                return;
            }

            // 查找已有 session_summary，获取已摘要条数
            Document existingSummary = findSessionSummary(conversationId);
            int previousSourceCount = 0;
            String existingText = null;
            if (existingSummary != null) {
                Object sc = existingSummary.getMetadata().get("source_count");
                previousSourceCount = sc instanceof Number ? ((Number) sc).intValue() : 0;
                existingText = existingSummary.getText();
            }

            // 只取增量部分（新溢出的 qa_pair）
            List<Document> newOverflowDocs = qaDocs.subList(previousSourceCount, overflowCount);

            log.info("Session summary triggered: conversationId={}, total={}, overflow={}, "
                            + "previousSourceCount={}, newBatch={}, step={}",
                    conversationId, qaDocs.size(), overflowCount,
                    previousSourceCount, newOverflowDocs.size(), step);

            String sessionSummary = generateSessionSummary(existingText, newOverflowDocs);
            if (sessionSummary == null || sessionSummary.isBlank()) {
                return;
            }
            deleteSessionSummaries(conversationId);
            vectorStore.add(List.of(Document.builder()
                    .id(UUID.randomUUID().toString())
                    .text(sessionSummary)
                    .metadata(buildSessionSummaryMetadata(userId, conversationId, overflowCount))
                    .build()));
            log.info("Session summary saved: conversationId={}, totalOverflow={}",
                    conversationId, overflowCount);
        } catch (Exception e) {
            log.error("Session summarization failed for conversationId={}: {}",
                    conversationId, e.getMessage(), e);
        }
    }

    // ==================== 未处理 qa_pair 查找 ====================

    /**
     * 取所有 cross_summary 的 latest_qa_created_at 最大值作为水位线，
     * qa_pair.created_at &gt; 水位线的即为未处理。
     */
    private List<Document> findUnprocessedQaPairDocs(String userId, int limit) {
        String watermark = getCrossSummaryWatermark(userId);
        List<Document> allQaDocs = findAllQaPairDocs(userId);

        return allQaDocs.stream()
                .filter(doc -> {
                    String ca = (String) doc.getMetadata().get("created_at");
                    return watermark == null || watermark.isEmpty() || ca.compareTo(watermark) > 0;
                })
                .limit(limit)
                .toList();
    }

    private String getCrossSummaryWatermark(String userId) {
        String watermark = null;
        for (Document doc : findAllCrossSummaryDocs(userId)) {
            String latest = (String) doc.getMetadata().get("latest_qa_created_at");
            if (latest != null && (watermark == null || latest.compareTo(watermark) > 0)) {
                watermark = latest;
            }
        }
        return watermark;
    }

    /**
     * 获取指定用户的所有 qa_pair（按 created_at 升序）。
     */
    private List<Document> findAllQaPairDocs(String userId) {
        FilterExpressionBuilder fb = new FilterExpressionBuilder();
        var filter = fb.and(
                fb.eq("user_id", userId),
                fb.eq("type", SemanticMemoryStore.DOC_TYPE_QA)
        ).build();

        List<Document> docs = new ArrayList<>(vectorStore.similaritySearch(SearchRequest.builder()
                .query("all qa pairs")
                .topK(MAX_QA_QUERY_LIMIT)
                .similarityThreshold(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL)
                .filterExpression(filter)
                .build()));
        sortByCreatedAt(docs);
        return docs;
    }

    /**
     * 获取指定用户的所有 cross_summary 文档。
     */
    private List<Document> findAllCrossSummaryDocs(String userId) {
        FilterExpressionBuilder fb = new FilterExpressionBuilder();
        var filter = fb.and(
                fb.eq("user_id", userId),
                fb.eq("type", SemanticMemoryStore.DOC_TYPE_CROSS_SUMMARY)
        ).build();

        return vectorStore.similaritySearch(SearchRequest.builder()
                .query("all cross summaries")
                .topK(200)
                .similarityThreshold(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL)
                .filterExpression(filter)
                .build());
    }

    /**
     * 获取指定会话的 qa_pair（按 created_at 升序，session_summary 专用）。
     */
    private List<Document> findQaPairDocsByConversation(String conversationId) {
        FilterExpressionBuilder fb = new FilterExpressionBuilder();
        var filter = fb.and(
                fb.eq("conversation_id", conversationId),
                fb.eq("type", SemanticMemoryStore.DOC_TYPE_QA)
        ).build();

        List<Document> docs = new ArrayList<>(vectorStore.similaritySearch(SearchRequest.builder()
                .query("all qa pairs")
                .topK(MAX_QA_QUERY_LIMIT)
                .similarityThreshold(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL)
                .filterExpression(filter)
                .build()));
        sortByCreatedAt(docs);
        return docs;
    }

    // ==================== 摘要生成与合并 ====================

    /**
     * Q&A → 摘要：生成 → 语义匹配已有摘要 → 合并 → 保存 → 删除旧摘要。
     * qa_pair 本身不动。即使 LLM 无产出，也写入标记文档推进水位线。
     */
    private void summarizeQaPairs(String userId, List<Document> qaDocs) {
        try {
            // 本批 qa_pair 的最新 created_at（水位线推进到此）
            String batchWatermark = qaDocs.stream()
                    .map(d -> (String) d.getMetadata().get("created_at"))
                    .filter(Objects::nonNull)
                    .max(String::compareTo)
                    .orElse(null);

            List<String> newSummaries = generateSummaries(qaDocs);

            if (newSummaries.isEmpty()) {
                log.debug("No valuable summaries for userId={}, advancing watermark to {}",
                        userId, batchWatermark);
                vectorStore.add(List.of(
                        buildCrossSummaryDoc(userId, "", batchWatermark)));
                return;
            }

            log.info("Generated {} summaries from {} qa_pairs for userId={}",
                    newSummaries.size(), qaDocs.size(), userId);

            List<Document> docsToSave = new ArrayList<>();
            List<String> idsToDelete = new ArrayList<>();

            for (String newSummary : newSummaries) {
                List<Document> relatedSummaries = findRelatedSummaries(userId, newSummary);

                if (relatedSummaries.isEmpty()) {
                    docsToSave.add(buildCrossSummaryDoc(userId, newSummary, batchWatermark));
                } else {
                    log.debug("Merging {} related summaries for userId={}",
                            relatedSummaries.size(), userId);
                    String merged = mergeSummaries(newSummary, relatedSummaries);
                    // 合并水位线：取最大值
                    String mergedWatermark = maxWatermark(batchWatermark, relatedSummaries);
                    relatedSummaries.stream().map(Document::getId).forEach(idsToDelete::add);
                    docsToSave.add(buildCrossSummaryDoc(userId, merged, mergedWatermark));
                }
            }

            if (!idsToDelete.isEmpty()) {
                vectorStore.delete(idsToDelete);
            }
            vectorStore.add(docsToSave);
            log.info("Saved {} cross_summary docs, deleted {} old for userId={}",
                    docsToSave.size(), idsToDelete.size(), userId);

        } catch (Exception e) {
            log.error("Summarization failed for userId={}: {}", userId, e.getMessage(), e);
        }
    }

    private String maxWatermark(String batchWatermark, List<Document> relatedSummaries) {
        String max = batchWatermark;
        for (Document doc : relatedSummaries) {
            String latest = (String) doc.getMetadata().get("latest_qa_created_at");
            if (latest != null && (max == null || latest.compareTo(max) > 0)) {
                max = latest;
            }
        }
        return max;
    }

    private List<Document> findRelatedSummaries(String userId, String summaryText) {
        FilterExpressionBuilder fb = new FilterExpressionBuilder();
        var filter = fb.and(
                fb.eq("user_id", userId),
                fb.eq("type", SemanticMemoryStore.DOC_TYPE_CROSS_SUMMARY)
        ).build();

        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(summaryText)
                .topK(5)
                .similarityThreshold(0.5)
                .filterExpression(filter)
                .build());
    }

    private String mergeSummaries(String newSummary, List<Document> relatedSummaries) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 新摘要\n").append(newSummary).append("\n\n");
        sb.append("## 已有摘要\n");
        for (int i = 0; i < relatedSummaries.size(); i++) {
            sb.append("### 摘要 ").append(i + 1).append("\n");
            sb.append(relatedSummaries.get(i).getText()).append("\n\n");
        }

        String result = ChatClient.builder(chatModel)
                .build()
                .prompt()
                .system(PromptConstants.SEMANTIC_SUMMARY_MERGE_PROMPT)
                .user(sb.toString())
                .call()
                .content();

        return result != null ? result.trim() : newSummary;
    }

    /**
     * 增量生成会话摘要。
     *
     * @param existingSummary 已有 session_summary 文本（首次为 null）
     * @param newOverflowDocs 本次新增的溢出 qa_pair（固定 step 条）
     */
    private String generateSessionSummary(String existingSummary, List<Document> newOverflowDocs) {
        StringBuilder sb = new StringBuilder();

        if (existingSummary != null && !existingSummary.isBlank()) {
            sb.append("## 已有会话摘要\n");
            sb.append(existingSummary).append("\n\n");
        }

        sb.append("## 本轮新增对话\n");
        for (int i = 0; i < newOverflowDocs.size(); i++) {
            String text = newOverflowDocs.get(i).getText();
            if (text != null && text.length() > MAX_QA_CONTENT_LENGTH) {
                text = text.substring(0, MAX_QA_CONTENT_LENGTH) + "...";
            }
            sb.append("### 对话 ").append(i + 1).append("\n");
            sb.append(text != null ? text : "").append("\n\n");
        }

        String userPrompt = existingSummary != null
                ? "请将以下「已有会话摘要」与「本轮新增对话」合并为一份完整的会话摘要：\n\n" + sb
                : "以下是需要压缩的对话：\n\n" + sb;

        String result = ChatClient.builder(chatModel)
                .build()
                .prompt()
                .system(PromptConstants.SESSION_SUMMARIZATION_PROMPT)
                .user(userPrompt)
                .call()
                .content();

        return result != null ? result.trim() : null;
    }

    private Document findSessionSummary(String conversationId) {
        FilterExpressionBuilder fb = new FilterExpressionBuilder();
        var filter = fb.and(
                fb.eq("conversation_id", conversationId),
                fb.eq("type", SemanticMemoryStore.DOC_TYPE_SESSION_SUMMARY)
        ).build();

        List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                .query("")
                .topK(1)
                .similarityThreshold(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL)
                .filterExpression(filter)
                .build());
        return docs.isEmpty() ? null : docs.get(0);
    }

    private void deleteSessionSummaries(String conversationId) {
        FilterExpressionBuilder fb = new FilterExpressionBuilder();
        var filter = fb.and(
                fb.eq("conversation_id", conversationId),
                fb.eq("type", SemanticMemoryStore.DOC_TYPE_SESSION_SUMMARY)
        ).build();

        List<Document> existing = vectorStore.similaritySearch(SearchRequest.builder()
                .query("")
                .topK(1)
                .similarityThreshold(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL)
                .filterExpression(filter)
                .build());
        if (!existing.isEmpty()) {
            vectorStore.delete(existing.stream().map(Document::getId).toList());
        }
    }

    // ==================== 批量 LLM 摘要 ====================

    private List<String> generateSummaries(List<Document> qaDocs) {
        List<String> allSummaries = new ArrayList<>();

        for (int start = 0; start < qaDocs.size(); start += SUMMARIZE_BATCH_SIZE) {
            int end = Math.min(start + SUMMARIZE_BATCH_SIZE, qaDocs.size());
            List<Document> batch = qaDocs.subList(start, end);

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < batch.size(); i++) {
                String text = batch.get(i).getText();
                if (text != null && text.length() > MAX_QA_CONTENT_LENGTH) {
                    text = text.substring(0, MAX_QA_CONTENT_LENGTH) + "...";
                }
                sb.append("### 对话 ").append(start + i + 1).append("\n");
                sb.append(text != null ? text : "").append("\n\n");
            }

            try {
                String result = ChatClient.builder(chatModel)
                        .build()
                        .prompt()
                        .system(PromptConstants.SEMANTIC_SUMMARIZATION_PROMPT)
                        .user("以下是需要合并的对话：\n\n" + sb)
                        .call()
                        .content();

                allSummaries.addAll(parseSummaries(result));
                log.debug("Summarized batch {}-{} of {} qa_pairs", start + 1, end, qaDocs.size());
            } catch (Exception e) {
                log.error("Summarization failed for batch {}-{}: {}", start + 1, end, e.getMessage());
            }
        }

        return allSummaries;
    }

    private List<String> parseSummaries(String jsonResult) {
        if (jsonResult == null || jsonResult.isBlank()) {
            return List.of();
        }

        String json = jsonResult.trim();
        if (json.startsWith("```")) {
            int start = json.indexOf('\n') + 1;
            int end = json.lastIndexOf("```");
            if (start > 0 && end > start) {
                json = json.substring(start, end).trim();
            }
        }

        if (!json.startsWith("[") || !json.endsWith("]")) {
            log.warn("Invalid summary JSON format: {}", json.substring(0, Math.min(200, json.length())));
            return List.of();
        }

        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            log.error("Failed to parse summaries: {}", e.getMessage());
            return List.of();
        }
    }

    // ==================== Metadata 构建 ====================

    private Map<String, Object> buildQaMetadata(String userId, String conversationId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", SemanticMemoryStore.DOC_TYPE_QA);
        metadata.put("user_id", userId);
        metadata.put("conversation_id", conversationId);
        metadata.put("created_at", LocalDateTime.now().format(DATE_FORMATTER));
        return metadata;
    }

    private Document buildCrossSummaryDoc(String userId, String summaryText, String latestQaCreatedAt) {
        return Document.builder()
                .id(UUID.randomUUID().toString())
                .text(summaryText)
                .metadata(Map.of(
                        "type", SemanticMemoryStore.DOC_TYPE_CROSS_SUMMARY,
                        "user_id", userId,
                        "latest_qa_created_at", latestQaCreatedAt != null ? latestQaCreatedAt : "",
                        "created_at", LocalDateTime.now().format(DATE_FORMATTER)))
                .build();
    }

    private Map<String, Object> buildSessionSummaryMetadata(String userId, String conversationId, int sourceCount) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", SemanticMemoryStore.DOC_TYPE_SESSION_SUMMARY);
        metadata.put("user_id", userId);
        metadata.put("conversation_id", conversationId);
        metadata.put("source_count", sourceCount);
        metadata.put("created_at", LocalDateTime.now().format(DATE_FORMATTER));
        return metadata;
    }

    // ==================== 工具方法 ====================

    private static void sortByCreatedAt(List<Document> docs) {
        docs.sort((a, b) -> {
            String ta = (String) a.getMetadata().get("created_at");
            String tb = (String) b.getMetadata().get("created_at");
            if (ta == null && tb == null) return 0;
            if (ta == null) return -1;
            if (tb == null) return 1;
            return ta.compareTo(tb);
        });
    }
}
