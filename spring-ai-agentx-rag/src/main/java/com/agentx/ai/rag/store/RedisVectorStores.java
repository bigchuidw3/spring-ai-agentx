package com.agentx.ai.rag.store;

import com.agentx.ai.rag.common.MetadataKeys;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import redis.clients.jedis.JedisPooled;

import java.util.ArrayList;
import java.util.List;

/**
 * RedisVectorStore 工厂。
 *
 * Redis 的 metadata 平铺存储，只有建索引时注册的字段才能跟随检索结果返回 metadata。
 * 本工厂默认注册全部契约字段，并完成索引初始化，调用方只需传入 Jedis 连接与 EmbeddingModel。
 *
 * 调用方自定义 metadata 如需回读或过滤，通过 extraFields 追加注册。
 *
 * @author bigchui
 */
public final class RedisVectorStores {

    public static final String DEFAULT_INDEX_NAME = "agentx-rag-vector";
    public static final String DEFAULT_PREFIX = "rag:";

    /**
     * 契约字段注册清单：按类型映射到 RediSearch 字段。
     * TAG 用于精确匹配，NUMERIC 用于数值过滤。
     */
    private static final RedisVectorStore.MetadataField[] CONTRACT_FIELDS = {
            // 父子检索链路
            RedisVectorStore.MetadataField.tag(MetadataKeys.DOCUMENT_ID),
            RedisVectorStore.MetadataField.tag(MetadataKeys.PARENT_CHUNK_ID),
            RedisVectorStore.MetadataField.tag(MetadataKeys.CHUNK_ROLE),
            RedisVectorStore.MetadataField.tag(MetadataKeys.CHUNK_GROUP_ID),
            // 溯源
            RedisVectorStore.MetadataField.tag(MetadataKeys.CHUNK_ID),
            RedisVectorStore.MetadataField.tag(MetadataKeys.FILE_NAME),
            RedisVectorStore.MetadataField.tag(MetadataKeys.CONTENT_TYPE),
            RedisVectorStore.MetadataField.tag(MetadataKeys.CREATED_AT),
            // 标题结构
            RedisVectorStore.MetadataField.tag(MetadataKeys.HEADING),
            RedisVectorStore.MetadataField.tag(MetadataKeys.HEADING_PATH),
            // 多模态标记
            RedisVectorStore.MetadataField.tag(MetadataKeys.IMG_URLS),
            RedisVectorStore.MetadataField.tag(MetadataKeys.OVERSIZED_IMG_REF),
            RedisVectorStore.MetadataField.tag(MetadataKeys.OVERSIZED_TABLE_REF),
            RedisVectorStore.MetadataField.tag(MetadataKeys.CONTAINS_IMG),
            RedisVectorStore.MetadataField.tag(MetadataKeys.CONTAINS_TABLE),
            // 数值字段
            RedisVectorStore.MetadataField.numeric(MetadataKeys.CHUNK_INDEX),
            RedisVectorStore.MetadataField.numeric(MetadataKeys.CHUNK_TOTAL),
            RedisVectorStore.MetadataField.numeric(MetadataKeys.SKIP_EMBEDDING),
            RedisVectorStore.MetadataField.numeric(MetadataKeys.HEADING_LEVEL),
            RedisVectorStore.MetadataField.numeric(MetadataKeys.TABLE_COUNT)
    };

    private RedisVectorStores() {
    }

    /**
     * 默认构建：契约字段全量注册，索引名 agentx-rag-vector，前缀 rag:。
     */
    public static RedisVectorStore create(JedisPooled jedis, EmbeddingModel embeddingModel) {
        return create(jedis, embeddingModel, DEFAULT_INDEX_NAME, DEFAULT_PREFIX);
    }

    /**
     * 自定义索引名与前缀，契约字段仍然全量注册。
     */
    public static RedisVectorStore create(JedisPooled jedis, EmbeddingModel embeddingModel,
                                          String indexName, String prefix) {
        return build(jedis, embeddingModel, indexName, prefix, new RedisVectorStore.MetadataField[0]);
    }

    /**
     * 完整构建：契约字段 + 调用方追加的自定义字段。
     */
    public static RedisVectorStore create(JedisPooled jedis, EmbeddingModel embeddingModel,
                                          String indexName, String prefix,
                                          RedisVectorStore.MetadataField... extraFields) {
        return build(jedis, embeddingModel, indexName, prefix, extraFields);
    }

    private static RedisVectorStore build(JedisPooled jedis, EmbeddingModel embeddingModel,
                                          String indexName, String prefix,
                                          RedisVectorStore.MetadataField... extraFields) {
        // Spring AI 的 metadataFields() 是覆盖语义而非追加，必须合并后一次性传入
        List<RedisVectorStore.MetadataField> allFields =
                new ArrayList<>(List.of(CONTRACT_FIELDS));
        if (extraFields != null) {
            for (RedisVectorStore.MetadataField field : extraFields) {
                if (field != null) {
                    allFields.add(field);
                }
            }
        }
        RedisVectorStore vectorStore = RedisVectorStore.builder(jedis, embeddingModel)
                .indexName(indexName)
                .prefix(prefix)
                .metadataFields(allFields)
                .initializeSchema(true)
                .build();
        try {
            vectorStore.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException("Redis 向量索引初始化失败: " + indexName, e);
        }
        return vectorStore;
    }
}
