package com.agentx.ai.core.memory.semantic;

import com.agentx.ai.core.prompt.PromptConstants;
import com.agentx.ai.core.memory.store.SemanticMemoryStore;
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
import java.util.UUID;

/**
 * 语义记忆管理器。
 *
 * 管理 VectorStore 中 Q&A 文档的存储、检索和摘要合并：
 * - 每次对话结束后，异步保存 Q&A 文档到 VectorStore
 * - 对话开始时，通过语义检索获取相关历史知识
 * - 当 Q&A 文档数量达到阈值时，异步触发摘要合并
 *
 * VectorStore 中的 Document 通过 metadata 区分类型：
 * - {@code type: "qa_pair"} — 原始 Q&A 文档
 * - {@code type: "summary"} — 摘要文档
 *
 * @author bigchui
 * 
 */
public class SemanticMemoryManager {

    private static final Logger log = LoggerFactory.getLogger(SemanticMemoryManager.class);

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final VectorStore vectorStore;
    private final ChatModel chatModel;
    private final int summarizeThreshold;

    public SemanticMemoryManager(SemanticMemoryStore semanticMemoryStore, ChatModel chatModel) {
        this.vectorStore = semanticMemoryStore.getVectorStore();
        this.chatModel = chatModel;
        this.summarizeThreshold = semanticMemoryStore.getSummarizeThreshold();
    }

    /**
     * 保存一组 Q&A 到 VectorStore。
     *
     * @param userId         用户标识
     * @param question       用户问题
     * @param answer         助手回答
     * @param conversationId 会话 ID
     */
    public void saveQaPair(String userId, String question, String answer, String conversationId) {
        String content = "Q: " + question + "\nA: " + answer;
        Map<String, Object> metadata = buildQaMetadata(userId, conversationId);
        Document doc = Document.builder()
                .id(UUID.randomUUID().toString())
                .text(content)
                .metadata(metadata)
                .build();

        vectorStore.add(List.of(doc));
        log.debug("Saved Q&A to VectorStore: userId={}, conversationId={}", userId, conversationId);
    }

    /**
     * 语义检索：根据查询获取相关历史知识。
     *
     * @param userId 用户标识
     * @param query  当前查询
     * @param topK   返回最多 topK 条结果
     * @return 相关文档列表
     */
    public List<Document> search(String userId, String query, int topK) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        var filter = b.eq("user_id", userId).build();

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(filter)
                .build();

        return vectorStore.similaritySearch(request);
    }

    /**
     * 检查是否达到摘要阈值，如果达到则触发摘要合并。
     *
     * 优化策略：先查 topK = summarizeThreshold 条，
     * 只有达到阈值时才表示需要摘要，此时这批数据直接用于摘要合并。
     *
     * @param userId 用户标识
     */
    public void checkAndSummarize(String userId) {
        // 第一步：只查阈值数量的文档，用于判断 + 摘要
        List<Document> qaDocs = findQaPairDocs(userId, summarizeThreshold);
        log.debug("Q&A document count for userId={}: {}/{}", userId, qaDocs.size(), summarizeThreshold);

        if (qaDocs.size() < summarizeThreshold) {
            return;
        }

        log.info("Summarize threshold reached for userId={}: {} >= {}", userId, qaDocs.size(), summarizeThreshold);
        summarizeQaPairs(userId, qaDocs);
    }

    /**
     * 获取指定用户的 Q&A 文档（限定数量）。
     *
     * @param userId 用户标识
     * @param limit  最大返回数量
     * @return Q&A 文档列表
     */
    private List<Document> findQaPairDocs(String userId, int limit) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        var filter = b.and(
                b.eq("user_id", userId),
                b.eq("type", SemanticMemoryStore.DOC_TYPE_QA)
        ).build();

        SearchRequest request = SearchRequest.builder()
                .query("")
                .topK(limit)
                .similarityThreshold(0.0)
                .filterExpression(filter)
                .build();

        return vectorStore.similaritySearch(request);
    }

    /**
     * Q&A 摘要合并流程：LLM 生成新摘要 → 语义搜索相关已有摘要 → 合并 → 保存。
     *
     * 核心流程：
     * 1. LLM 将 qa_pair 批量生成为新摘要
     * 2. 对每条新摘要，语义搜索主题相关的已有摘要
     * 3. 如果找到相关摘要 → LLM 合并为一条 → 删除旧摘要，保存合并结果
     * 4. 如果没有相关摘要 → 直接保存新摘要
     * 5. 删除原始 qa_pair 文档
     *
     * 这样保证：
     * - 同主题的摘要会被持续合并精炼，不会无限增长
     * - 不同主题的摘要互不干扰，不会错误合并
     */
    private void summarizeQaPairs(String userId, List<Document> qaDocs) {
        try {
            // 1. LLM 从 qa_pair 生成新摘要
            List<String> newSummaries = generateSummaries(qaDocs);
            if (newSummaries.isEmpty()) {
                log.debug("No valuable summaries generated for userId={}", userId);
                // 即使没有生成有价值的摘要，也删除原始 qa_pair
                deleteDocs(qaDocs);
                return;
            }

            log.info("Generated {} new summaries from {} qa_pairs for userId={}",
                    newSummaries.size(), qaDocs.size(), userId);

            // 2. 对每条新摘要，查找相关的已有摘要并合并
            List<String> idsToDelete = new ArrayList<>();
            List<Document> docsToSave = new ArrayList<>();

            for (String newSummary : newSummaries) {
                // 语义搜索相关的已有摘要
                List<Document> relatedSummaries = findRelatedSummaries(userId, newSummary);

                if (relatedSummaries.isEmpty()) {
                    // 无相关摘要 → 直接保存新摘要
                    docsToSave.add(buildSummaryDoc(userId, newSummary, 1));
                } else {
                    // 有相关摘要 → LLM 合并
                    log.debug("Found {} related summaries to merge for userId={}",
                            relatedSummaries.size(), userId);
                    String merged = mergeSummaries(newSummary, relatedSummaries);
                    relatedSummaries.stream().map(Document::getId).forEach(idsToDelete::add);
                    docsToSave.add(buildSummaryDoc(userId, merged, 1 + relatedSummaries.size()));
                }
            }

            // 3. 删除原始 qa_pair + 被合并的旧摘要
            qaDocs.stream().map(Document::getId).forEach(idsToDelete::add);
            if (!idsToDelete.isEmpty()) {
                vectorStore.delete(idsToDelete);
                log.info("Deleted {} documents for userId={} ({} qa_pairs + {} merged summaries)",
                        idsToDelete.size(), userId, qaDocs.size(),
                        idsToDelete.size() - qaDocs.size());
            }

            // 4. 保存新摘要（合并后或直接保存的）
            vectorStore.add(docsToSave);
            log.info("Saved {} summaries for userId={}", docsToSave.size(), userId);

        } catch (Exception e) {
            log.error("Summarization failed for userId={}: {}", userId, e.getMessage(), e);
        }
    }

    /**
     * 语义搜索：根据文本查找相关的已有摘要。
     *
     * @param userId       用户标识
     * @param summaryText  新摘要文本（用作查询）
     * @return 主题相关的已有摘要列表
     */
    private List<Document> findRelatedSummaries(String userId, String summaryText) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        var filter = b.and(
                b.eq("user_id", userId),
                b.eq("type", SemanticMemoryStore.DOC_TYPE_SUMMARY)
        ).build();

        SearchRequest request = SearchRequest.builder()
                .query(summaryText)
                .topK(5)
                .similarityThreshold(0.5)
                .filterExpression(filter)
                .build();

        return vectorStore.similaritySearch(request);
    }

    /**
     * LLM 合并新摘要与相关已有摘要。
     *
     * @param newSummary       新生成的摘要
     * @param relatedSummaries 语义相关的已有摘要
     * @return 合并后的摘要文本
     */
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

    private Document buildSummaryDoc(String userId, String summaryText, int sourceCount) {
        return Document.builder()
                .id(UUID.randomUUID().toString())
                .text(summaryText)
                .metadata(buildSummaryMetadata(userId, sourceCount))
                .build();
    }

    private void deleteDocs(List<Document> docs) {
        List<String> ids = docs.stream().map(Document::getId).toList();
        if (!ids.isEmpty()) {
            vectorStore.delete(ids);
        }
    }

    /**
     * 使用 LLM 将 Q&A 文档合并为摘要。
     */
    private List<String> generateSummaries(List<Document> qaDocs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < qaDocs.size(); i++) {
            sb.append("### 对话 ").append(i + 1).append("\n");
            sb.append(qaDocs.get(i).getText()).append("\n\n");
        }

        String result = ChatClient.builder(chatModel)
                .build()
                .prompt()
                .system(PromptConstants.SEMANTIC_SUMMARIZATION_PROMPT)
                .user("以下是需要合并的对话：\n\n" + sb)
                .call()
                .content();

        return parseSummaries(result);
    }

    /**
     * 解析 LLM 返回的摘要 JSON 数组。
     */
    private List<String> parseSummaries(String jsonResult) {
        if (jsonResult == null || jsonResult.isBlank()) {
            return List.of();
        }

        String json = jsonResult.trim();
        // 处理 markdown code block
        if (json.startsWith("```")) {
            int start = json.indexOf('\n') + 1;
            int end = json.lastIndexOf("```");
            if (start > 0 && end > start) {
                json = json.substring(start, end).trim();
            }
        }

        // 简单的 JSON 数组解析（避免引入额外依赖）
        if (!json.startsWith("[") || !json.endsWith("]")) {
            log.warn("Invalid summary JSON format, skipping: {}", json.substring(0, Math.min(200, json.length())));
            return List.of();
        }

        try {
            List<String> summaries = new ArrayList<>();
            // 手动解析简单的字符串数组
            String inner = json.substring(1, json.length() - 1).trim();
            if (inner.isEmpty()) {
                return List.of();
            }

            // 使用 com.fasterxml.jackson 解析
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<String> parsed = mapper.readValue(json, mapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return parsed;
        } catch (Exception e) {
            log.error("Failed to parse summaries: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> buildQaMetadata(String userId, String conversationId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", SemanticMemoryStore.DOC_TYPE_QA);
        metadata.put("user_id", userId);
        metadata.put("conversation_id", conversationId);
        metadata.put("created_at", LocalDateTime.now().format(DATE_FORMATTER));
        return metadata;
    }

    private Map<String, Object> buildSummaryMetadata(String userId, int sourceCount) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", SemanticMemoryStore.DOC_TYPE_SUMMARY);
        metadata.put("user_id", userId);
        metadata.put("source_count", sourceCount);
        metadata.put("created_at", LocalDateTime.now().format(DATE_FORMATTER));
        return metadata;
    }
}
