package com.agentx.ai.rag.retrieve.reranker;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * RAG 重排 SPI。
 *
 * 对检索召回的候选集做 query-document 相关度二次打分，截断数量由实现自己决定。
 * 不传实现则跳过重排，不影响主流程。
 *
 * @author bigchui
 */
public interface Reranker {

    List<Document> rerank(String query, List<Document> candidates);
}
