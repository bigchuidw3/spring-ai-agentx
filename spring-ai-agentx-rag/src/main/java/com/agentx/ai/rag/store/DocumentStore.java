package com.agentx.ai.rag.store;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * RAG 父块原文回查存储 SPI。
 *
 * 仅父子分块场景需要：命中子块后按 parentChunkId 精确取回父块完整上下文。
 * 未配置时不存父块，父子回查退化为普通子块检索。典型实现：Redis、关系型数据库。
 *
 * @author bigchui
 */
public interface DocumentStore {

    /**
     * 保存父块原文，chunkId 作为唯一键。
     */
    void save(List<Document> documents);

    /**
     * 按 chunkId 取回父块原文，不存在时返回 null。
     */
    Document get(String chunkId);

    /**
     * 按 documentId 删除该文档下的所有父块。
     */
    void deleteByDocumentId(String documentId);
}
