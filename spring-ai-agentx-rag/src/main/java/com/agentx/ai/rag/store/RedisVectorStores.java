package com.agentx.ai.rag.store;

import com.agentx.ai.rag.common.MetadataKeys;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import redis.clients.jedis.JedisPooled;

/**
 * RedisVectorStore 工厂。
 *
 * Redis 的 metadata 平铺存储，只有建索引时注册的字段才能跟随检索结果返回 metadata
 * 并用于 filter 过滤。本工厂把检索链路依赖的契约字段默认注册，并完成索引初始化，
 * 调用方只需传入 Jedis 连接与 EmbeddingModel。
 *
 * 调用方自定义 metadata 如需过滤或回读，通过 extraFields 追加。
 *
 * @author bigchui
 */
public final class RedisVectorStores {

    public static final String DEFAULT_INDEX_NAME = "agentx-rag-vector";
    public static final String DEFAULT_PREFIX = "rag:";

    private RedisVectorStores() {
    }

    /**
     * 默认构建：契约字段已注册，索引名 agentx-rag-vector，前缀 rag:。
     */
    public static RedisVectorStore create(JedisPooled jedis, EmbeddingModel embeddingModel) {
        return create(jedis, embeddingModel, DEFAULT_INDEX_NAME, DEFAULT_PREFIX);
    }

    /**
     * 自定义索引名与前缀，契约字段仍然默认注册。
     */
    public static RedisVectorStore create(JedisPooled jedis, EmbeddingModel embeddingModel,
                                          String indexName, String prefix) {
        return build(jedis, embeddingModel, indexName, prefix, new RedisVectorStore.MetadataField[0]);
    }

    /**
     * 完整构建：契约字段 + 调用方追加的自定义过滤字段。
     */
    public static RedisVectorStore create(JedisPooled jedis, EmbeddingModel embeddingModel,
                                          String indexName, String prefix,
                                          RedisVectorStore.MetadataField... extraFields) {
        return build(jedis, embeddingModel, indexName, prefix, extraFields);
    }

    private static RedisVectorStore build(JedisPooled jedis, EmbeddingModel embeddingModel,
                                          String indexName, String prefix,
                                          RedisVectorStore.MetadataField... extraFields) {
        RedisVectorStore.Builder builder = RedisVectorStore.builder(jedis, embeddingModel)
                .indexName(indexName)
                .prefix(prefix)
                .metadataFields(
                        RedisVectorStore.MetadataField.tag(MetadataKeys.DOCUMENT_ID),
                        RedisVectorStore.MetadataField.tag(MetadataKeys.PARENT_CHUNK_ID),
                        RedisVectorStore.MetadataField.tag(MetadataKeys.CHUNK_ROLE),
                        RedisVectorStore.MetadataField.tag(MetadataKeys.CHUNK_GROUP_ID));
        for (RedisVectorStore.MetadataField field : extraFields) {
            if (field != null) {
                builder.metadataFields(field);
            }
        }
        RedisVectorStore vectorStore = builder.initializeSchema(true).build();
        try {
            vectorStore.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException("Redis 向量索引初始化失败: " + indexName, e);
        }
        return vectorStore;
    }
}
