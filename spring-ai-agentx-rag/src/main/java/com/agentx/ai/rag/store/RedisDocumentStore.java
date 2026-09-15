package com.agentx.ai.rag.store;

import com.agentx.ai.rag.common.MetadataKeys;
import com.agentx.ai.rag.exception.RagErrorCode;
import com.agentx.ai.rag.exception.RagException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 基于 Redis 的父块原文存储。
 *
 * chunkId 作为 key，Document 序列化为 JSON 字符串存储，天然持久化、重启不丢，
 * 适合多实例共享场景。
 *
 * @author bigchui
 */
public final class RedisDocumentStore implements DocumentStore {

    private static final String KEY_PREFIX = "agentx:rag:document:";

    private static final String INDEX_KEY_PREFIX = "agentx:rag:document:index:";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final StringRedisTemplate redisTemplate;

    public RedisDocumentStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
    }

    @Override
    public void save(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        for (Document doc : documents) {
            if (doc == null || doc.getId() == null) {
                continue;
            }
            redisTemplate.opsForValue().set(key(doc.getId()), toJson(doc));
            String documentId = asString(doc.getMetadata().get(MetadataKeys.DOCUMENT_ID));
            if (documentId != null) {
                redisTemplate.opsForSet().add(indexKey(documentId), doc.getId());
            }
        }
    }

    @Override
    public Document get(String chunkId) {
        if (chunkId == null) {
            return null;
        }
        String json = redisTemplate.opsForValue().get(key(chunkId));
        return json == null ? null : fromJson(json);
    }

    @Override
    public void deleteByDocumentId(String documentId) {
        if (documentId == null) {
            return;
        }
        String indexKey = indexKey(documentId);
        Set<String> chunkIds = redisTemplate.opsForSet().members(indexKey);
        if (chunkIds != null && !chunkIds.isEmpty()) {
            redisTemplate.delete(chunkIds.stream().map(RedisDocumentStore::key).toList());
        }
        redisTemplate.delete(indexKey);
    }

    private static String key(String chunkId) {
        return KEY_PREFIX + chunkId;
    }

    private static String indexKey(String documentId) {
        return INDEX_KEY_PREFIX + documentId;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static String toJson(Document doc) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", doc.getId());
        map.put("text", doc.getText());
        map.put("metadata", doc.getMetadata());
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            throw new RagException(RagErrorCode.STORE_WRITE_FAILED, "父块序列化失败: " + doc.getId(), e);
        }
    }

    private static Document fromJson(String json) {
        try {
            Map<String, Object> map = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
            String id = (String) map.get("id");
            String text = (String) map.get("text");
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) map.get("metadata");

            return Document.builder()
                    .id(id)
                    .text(text)
                    .metadata(metadata == null ? new LinkedHashMap<>() : metadata)
                    .build();
        } catch (Exception e) {
            throw new RagException(RagErrorCode.STORE_READ_FAILED, "父块反序列化失败", e);
        }
    }
}
