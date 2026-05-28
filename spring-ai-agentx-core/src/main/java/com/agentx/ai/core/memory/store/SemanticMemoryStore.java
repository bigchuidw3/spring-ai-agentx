package com.agentx.ai.core.memory.store;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.Objects;

/**
 * 语义记忆存储配置。
 *
 * 封装 VectorStore 和 EmbeddingModel，传入 {@link com.agentx.ai.core.agent.ReactAgent.Builder}。
 * 框架根据此配置启用第三层记忆：语义长期记忆（RAG 检索）。
 *
 * 不传入此配置时，语义记忆不启用，只有短期记忆和用户画像。
 *
 * VectorStore 中的 Document 通过 metadata 区分类型：
 * - {@code type: "qa_pair"} — 原始 Q&A 文档（每次对话后异步写入）
 * - {@code type: "summary"} — 摘要文档（达到阈值后合并生成）
 *
 * @author bigchui
 * 
 */
public class SemanticMemoryStore {

    /** 文档元数据中的类型标识：原始 Q&A */
    public static final String DOC_TYPE_QA = "qa_pair";

    /** 文档元数据中的类型标识：摘要 */
    public static final String DOC_TYPE_SUMMARY = "summary";

    /** 默认摘要触发阈值 */
    public static final int DEFAULT_SUMMARIZE_THRESHOLD = 30;

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final int summarizeThreshold;

    public SemanticMemoryStore(VectorStore vectorStore, EmbeddingModel embeddingModel) {
        this(vectorStore, embeddingModel, DEFAULT_SUMMARIZE_THRESHOLD);
    }

    public SemanticMemoryStore(VectorStore vectorStore, EmbeddingModel embeddingModel, int summarizeThreshold) {
        Objects.requireNonNull(vectorStore, "vectorStore must not be null");
        Objects.requireNonNull(embeddingModel, "embeddingModel must not be null");
        if (summarizeThreshold <= 0) {
            throw new IllegalArgumentException("summarizeThreshold must be positive, got: " + summarizeThreshold);
        }
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        this.summarizeThreshold = summarizeThreshold;
    }

    public VectorStore getVectorStore() {
        return vectorStore;
    }

    public EmbeddingModel getEmbeddingModel() {
        return embeddingModel;
    }

    public int getSummarizeThreshold() {
        return summarizeThreshold;
    }
}
