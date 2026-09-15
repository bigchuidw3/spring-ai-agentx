package com.agentx.ai.rag.retrieve;

import com.agentx.ai.rag.common.MetadataKeys;
import com.agentx.ai.rag.store.DocumentStore;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 父子检索器。
 *
 * 装饰任意 DocumentRetriever，命中子块后按 parentChunkId 从 DocumentStore 回查父块，
 * 多个子块命中同一父块时自动去重。复用 Spring AI 的 DocumentRetriever 接口。
 *
 * @author bigchui
 */
public final class ParentChildDocumentRetriever implements DocumentRetriever {

    private final DocumentRetriever baseRetriever;
    private final DocumentStore documentStore;

    public ParentChildDocumentRetriever(DocumentRetriever baseRetriever, DocumentStore documentStore) {
        this.baseRetriever = Objects.requireNonNull(baseRetriever, "baseRetriever");
        this.documentStore = Objects.requireNonNull(documentStore, "documentStore");
    }

    @Override
    public List<Document> retrieve(Query query) {
        List<Document> children = baseRetriever.retrieve(query);
        Set<String> seen = new HashSet<>();
        List<Document> parents = new ArrayList<>();
        for (Document child : children) {
            String parentId = asString(child.getMetadata().get(MetadataKeys.PARENT_CHUNK_ID));
            if (parentId == null || !seen.add(parentId)) {
                continue;
            }
            Document parent = documentStore.get(parentId);
            if (parent != null) {
                parents.add(parent);
            }
        }
        return parents;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
