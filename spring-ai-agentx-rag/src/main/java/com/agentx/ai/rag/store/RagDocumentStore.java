package com.agentx.ai.rag.store;

import com.agentx.ai.rag.common.MetadataKeys;
import com.agentx.ai.rag.exception.RagErrorCode;
import com.agentx.ai.rag.exception.RagException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 文档写入路由门面。
 *
 * 按 skipEmbedding 把 chunk 分流到 VectorStore、DocumentStore，
 * 调用方只负责在 Builder 里传数据源，未配置的源自动跳过。
 *
 * @author bigchui
 */
public final class RagDocumentStore {

    private static final Logger log = LoggerFactory.getLogger(RagDocumentStore.class);

    /**
     * 单次 embedding 调用最大文档数，默认按 dashscope text-embedding-v3 上限。
     */
    private static final int DEFAULT_EMBEDDING_BATCH_SIZE = 10;

    private static final DateTimeFormatter INDEX_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final VectorStore vectorStore;
    private final DocumentStore documentStore;
    private final int embeddingBatchSize;

    private RagDocumentStore(Builder builder) {
        this.vectorStore = builder.vectorStore;
        this.documentStore = builder.documentStore;
        this.embeddingBatchSize = builder.embeddingBatchSize;
    }

    /**
     * 构建索引：先按 documentId 清掉旧数据，再重新写入。
     * 普通子块进 VectorStore，skipEmbedding=1 的父块只进 DocumentStore。
     */
    public void index(String documentId, List<Document> documents) {
        index(documentId, documents, null);
    }

    /**
     * 构建索引，并给所有块统一注入调用方自定义 metadata（如 docType）。
     * 自定义 metadata 会随块透传到各数据源，检索时可用于过滤。
     */
    public void index(String documentId, List<Document> documents, Map<String, Object> extraMetadata) {
        if (documentId == null || documentId.isBlank()) {
            throw new RagException(RagErrorCode.STORE_CONFIG_INVALID, "documentId 不能为空");
        }
        if (documents == null || documents.isEmpty()) {
            return;
        }

        deleteByDocumentId(documentId);

        String createdAt = LocalDateTime.now().withNano(0).format(INDEX_TIME_FORMATTER);
        List<Document> embedChunks = new ArrayList<>();
        List<Document> parentChunks = new ArrayList<>();
        for (Document doc : documents) {
            if (doc == null) {
                continue;
            }
            Document normalized = withIndexMetadata(doc, documentId, createdAt, extraMetadata);
            if (isSkipEmbedding(normalized)) {
                parentChunks.add(normalized);
            } else {
                embedChunks.add(normalized);
            }
        }

        if (!embedChunks.isEmpty()) {
            for (List<Document> batch : partition(embedChunks, embeddingBatchSize)) {
                vectorStore.add(batch);
            }
        }

        if (!parentChunks.isEmpty()) {
            if (documentStore != null) {
                documentStore.save(parentChunks);
            } else {
                log.warn("[RagDocumentStore] 检测到 {} 个 skipEmbedding=1 的父块，但未配置 DocumentStore，父块未存储，父子回查不可用",
                        parentChunks.size());
            }
        }
    }

    /**
     * 按 documentId 删除该文档下的所有数据。
     */
    public void deleteByDocumentId(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            return;
        }
        vectorStore.delete(new FilterExpressionBuilder()
                .eq(MetadataKeys.DOCUMENT_ID, documentId)
                .build());
        if (documentStore != null) {
            documentStore.deleteByDocumentId(documentId);
        }
    }

    private static Document withIndexMetadata(Document doc, String documentId, String createdAt,
                                              Map<String, Object> extraMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>(doc.getMetadata());
        metadata.put(MetadataKeys.DOCUMENT_ID, documentId);
        metadata.put(MetadataKeys.CREATED_AT, createdAt);
        if (extraMetadata != null) {
            metadata.putAll(extraMetadata);
        }
        Document.Builder builder = Document.builder()
                .id(doc.getId())
                .text(doc.getText())
                .metadata(metadata);
        if (doc.getScore() != null) {
            builder.score(doc.getScore());
        }
        return builder.build();
    }

    private static boolean isSkipEmbedding(Document doc) {
        Object skip = doc.getMetadata().get(MetadataKeys.SKIP_EMBEDDING);
        return Integer.valueOf(1).equals(skip);
    }

    private static List<List<Document>> partition(List<Document> documents, int batchSize) {
        List<List<Document>> batches = new ArrayList<>();
        for (int i = 0; i < documents.size(); i += batchSize) {
            batches.add(documents.subList(i, Math.min(i + batchSize, documents.size())));
        }
        return batches;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private VectorStore vectorStore;
        private DocumentStore documentStore;
        private int embeddingBatchSize = DEFAULT_EMBEDDING_BATCH_SIZE;

        public Builder vectorStore(VectorStore vectorStore) {
            this.vectorStore = vectorStore;
            return this;
        }

        public Builder documentStore(DocumentStore documentStore) {
            this.documentStore = documentStore;
            return this;
        }

        /**
         * 单次 embedding 调用最大文档数，需匹配模型限制。
         */
        public Builder embeddingBatchSize(int embeddingBatchSize) {
            this.embeddingBatchSize = embeddingBatchSize;
            return this;
        }

        public RagDocumentStore build() {
            if (vectorStore == null) {
                throw new RagException(RagErrorCode.STORE_CONFIG_INVALID, "vectorStore 为必填项");
            }
            if (embeddingBatchSize <= 0) {
                throw new RagException(RagErrorCode.STORE_CONFIG_INVALID,
                        "embeddingBatchSize 必须大于 0: " + embeddingBatchSize);
            }
            return new RagDocumentStore(this);
        }
    }
}
