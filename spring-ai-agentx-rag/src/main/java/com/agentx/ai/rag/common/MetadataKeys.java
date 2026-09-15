package com.agentx.ai.rag.common;

/**
 * RAG 文档 metadata 契约。
 *
 * <p>这些字段贯穿分块、写入、召回和来源追溯，调用方也可以追加自定义 metadata。
 *
 * @author bigchui
 */
public final class MetadataKeys {

    private MetadataKeys() {
    }

    public static final String CHUNK_ID = "chunkId";
    public static final String PARENT_CHUNK_ID = "parentChunkId";
    public static final String CHUNK_ROLE = "chunkRole";
    public static final String CHUNK_GROUP_ID = "chunkGroupId";
    public static final String CHUNK_INDEX = "chunkIndex";
    public static final String CHUNK_TOTAL = "chunkTotal";
    public static final String SKIP_EMBEDDING = "skipEmbedding";
    public static final String HEADING_LEVEL = "headingLevel";
    public static final String HEADING = "heading";
    public static final String HEADING_PATH = "headingPath";
    public static final String DOCUMENT_ID = "documentId";
    public static final String FILE_NAME = "fileName";
    public static final String CREATED_AT = "createdAt";
    public static final String CONTENT_TYPE = "contentType";
    public static final String CONTAINS_IMG = "containsImg";
    public static final String IMG_URLS = "imgUrls";
    public static final String OVERSIZED_IMG_REF = "oversizedImgRef";
    public static final String CONTAINS_TABLE = "containsTable";
    public static final String TABLE_COUNT = "tableCount";
    public static final String OVERSIZED_TABLE_REF = "oversizedTableRef";
}
