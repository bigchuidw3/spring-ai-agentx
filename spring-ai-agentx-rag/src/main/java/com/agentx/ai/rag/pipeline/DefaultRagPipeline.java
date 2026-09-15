package com.agentx.ai.rag.pipeline;

import com.agentx.ai.rag.exception.RagErrorCode;
import com.agentx.ai.rag.exception.RagException;
import com.agentx.ai.rag.retrieve.reranker.Reranker;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RAG 检索编排默认实现，骨架固定不可变：
 *
 * transformer 链串行（上一步输出作为下一步输入）→ expander 展开（未配置则单 query）
 * → 逐 query 检索并按文档 ID 去重 → reranker 精排（未配置跳过）。
 *
 * 调用方只往固定槽位里组装组件，槽位顺序由框架决定。
 *
 * @author bigchui
 */
public final class DefaultRagPipeline implements RagPipeline {

    private final List<QueryTransformer> queryTransformers;
    private final QueryExpander queryExpander;
    private final DocumentRetriever documentRetriever;
    private final Reranker reranker;

    private DefaultRagPipeline(Builder builder) {
        this.queryTransformers = List.copyOf(builder.queryTransformers);
        this.queryExpander = builder.queryExpander;
        this.documentRetriever = builder.documentRetriever;
        this.reranker = builder.reranker;
    }

    @Override
    public List<Document> retrieve(String question, List<Message> chatHistory) {
        if (question == null || question.isBlank()) {
            return List.of();
        }

        Query query = Query.builder()
                .text(question)
                .history(chatHistory == null ? List.of() : chatHistory)
                .build();

        for (QueryTransformer transformer : queryTransformers) {
            query = transformer.transform(query);
        }

        List<Query> queries = queryExpander == null ? List.of(query) : queryExpander.expand(query);

        List<Document> documents = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (Query q : queries) {
            for (Document document : documentRetriever.retrieve(q)) {
                if (seenIds.add(document.getId())) {
                    documents.add(document);
                }
            }
        }

        if (reranker != null && !documents.isEmpty()) {
            return reranker.rerank(question, documents);
        }
        return documents;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final List<QueryTransformer> queryTransformers = new ArrayList<>();
        private QueryExpander queryExpander;
        private DocumentRetriever documentRetriever;
        private Reranker reranker;

        /**
         * 追加 1→1 查询转换器，按追加顺序串行执行。
         */
        public Builder queryTransformer(QueryTransformer transformer) {
            if (transformer != null) {
                this.queryTransformers.add(transformer);
            }
            return this;
        }

        public Builder queryExpander(QueryExpander queryExpander) {
            this.queryExpander = queryExpander;
            return this;
        }

        public Builder documentRetriever(DocumentRetriever documentRetriever) {
            this.documentRetriever = documentRetriever;
            return this;
        }

        /**
         * 重排器，截断数量由重排器自身决定。
         */
        public Builder reranker(Reranker reranker) {
            this.reranker = reranker;
            return this;
        }

        public DefaultRagPipeline build() {
            if (documentRetriever == null) {
                throw new RagException(RagErrorCode.PIPELINE_CONFIG_INVALID, "documentRetriever 为必填项");
            }
            return new DefaultRagPipeline(this);
        }
    }
}
