package com.agentx.ai.rag.retrieve;

import com.agentx.ai.rag.exception.RagErrorCode;
import com.agentx.ai.rag.exception.RagException;
import com.agentx.ai.rag.store.DocumentStore;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.Objects;

/**
 * 增强检索器。
 *
 * 统一封装向量检索与父子召回：enableParentChild 开启时命中子块回查父块，
 * 关闭时退化为纯向量检索。调用方无需在 VectorStoreDocumentRetriever 与
 * ParentChildDocumentRetriever 之间自行选择。
 *
 * @author bigchui
 */
public final class EnhancedRetriever implements DocumentRetriever {

    private final DocumentRetriever delegate;

    private EnhancedRetriever(Builder builder) {
        VectorStoreDocumentRetriever.Builder vectorBuilder = VectorStoreDocumentRetriever.builder()
                .vectorStore(builder.vectorStore)
                .topK(builder.topK);
        if (builder.similarityThreshold != null) {
            vectorBuilder.similarityThreshold(builder.similarityThreshold);
        }
        if (builder.filterExpression != null) {
            vectorBuilder.filterExpression(builder.filterExpression);
        }

        VectorStoreDocumentRetriever vectorRetriever = vectorBuilder.build();
        if (builder.enableParentChild) {
            this.delegate = new ParentChildDocumentRetriever(vectorRetriever, builder.documentStore);
        } else {
            this.delegate = vectorRetriever;
        }
    }

    @Override
    public List<Document> retrieve(Query query) {
        return delegate.retrieve(query);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private VectorStore vectorStore;
        private DocumentStore documentStore;
        private int topK = 5;
        private Double similarityThreshold;
        private Filter.Expression filterExpression;
        private boolean enableParentChild;

        public Builder vectorStore(VectorStore vectorStore) {
            this.vectorStore = vectorStore;
            return this;
        }

        public Builder documentStore(DocumentStore documentStore) {
            this.documentStore = documentStore;
            return this;
        }

        public Builder topK(int topK) {
            this.topK = topK;
            return this;
        }

        public Builder similarityThreshold(Double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
            return this;
        }

        public Builder filterExpression(Filter.Expression filterExpression) {
            this.filterExpression = filterExpression;
            return this;
        }

        /**
         * 开启父子召回：命中子块后回查父块完整小节。需同时配置 documentStore。
         */
        public Builder enableParentChild(boolean enableParentChild) {
            this.enableParentChild = enableParentChild;
            return this;
        }

        public EnhancedRetriever build() {
            Objects.requireNonNull(vectorStore, "vectorStore 为必填项");
            if (topK <= 0) {
                throw new RagException(RagErrorCode.RETRIEVER_CONFIG_INVALID,
                        "topK 必须大于 0: " + topK);
            }
            if (enableParentChild && documentStore == null) {
                throw new RagException(RagErrorCode.RETRIEVER_CONFIG_INVALID,
                        "开启父子召回时 documentStore 为必填项");
            }
            return new EnhancedRetriever(this);
        }
    }
}
