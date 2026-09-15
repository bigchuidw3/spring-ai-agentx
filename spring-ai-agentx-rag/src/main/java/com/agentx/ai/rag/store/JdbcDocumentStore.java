package com.agentx.ai.rag.store;

import com.agentx.ai.rag.common.MetadataKeys;
import com.agentx.ai.rag.exception.RagErrorCode;
import com.agentx.ai.rag.exception.RagException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 基于 JDBC 的父块原文存储，面向 PostgreSQL，可与 pgvector 同库。
 *
 * chunkId 作为主键，metadata 序列化为 JSON 文本列，持久化、重启不丢。
 * 表名默认 agentx_rag_document，可通过构造参数覆盖。
 *
 * @author bigchui
 */
public final class JdbcDocumentStore implements DocumentStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcDocumentStore.class);

    private static final String DEFAULT_TABLE_NAME = "agentx_rag_document";

    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS %s (
                chunk_id    VARCHAR(100) PRIMARY KEY,
                document_id VARCHAR(200) NOT NULL,
                text        TEXT NOT NULL,
                metadata    TEXT
            )
            """;

    private static final String CREATE_INDEX_SQL = "CREATE INDEX IF NOT EXISTS %s ON %s (document_id)";

    private static final String UPSERT_SQL = """
            INSERT INTO %s (chunk_id, document_id, text, metadata)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (chunk_id) DO UPDATE SET
                document_id = EXCLUDED.document_id,
                text        = EXCLUDED.text,
                metadata    = EXCLUDED.metadata
            """;

    private static final String SELECT_SQL = """
            SELECT chunk_id, text, metadata
            FROM %s
            WHERE chunk_id = ?
            """;

    private static final String DELETE_BY_DOCUMENT_SQL = "DELETE FROM %s WHERE document_id = ?";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final JdbcTemplate jdbcTemplate;
    private final String tableName;
    private volatile boolean initialized;

    public JdbcDocumentStore(DataSource dataSource) {
        this(dataSource, DEFAULT_TABLE_NAME);
    }

    public JdbcDocumentStore(DataSource dataSource, String tableName) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        if (tableName == null || !TABLE_NAME_PATTERN.matcher(tableName).matches()) {
            throw new RagException(RagErrorCode.STORE_CONFIG_INVALID, "非法表名: " + tableName);
        }
        this.tableName = tableName;
    }

    /**
     * 建表建索引，幂等、线程安全。
     */
    public void initialize() {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }
            jdbcTemplate.execute(CREATE_TABLE_SQL.formatted(tableName));
            jdbcTemplate.execute(CREATE_INDEX_SQL.formatted(indexName(), tableName));
            initialized = true;
            log.info("[JdbcDocumentStore] 表 {} 就绪", tableName);
        }
    }

    private void ensureInitialized() {
        if (!initialized) {
            initialize();
        }
    }

    private String indexName() {
        return "idx_" + tableName + "_document_id";
    }

    @Override
    public void save(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        ensureInitialized();
        for (Document doc : documents) {
            if (doc == null || doc.getId() == null) {
                continue;
            }
            jdbcTemplate.update(UPSERT_SQL.formatted(tableName),
                    doc.getId(),
                    doc.getMetadata().get(MetadataKeys.DOCUMENT_ID),
                    doc.getText(),
                    toJson(doc.getMetadata()));
        }
    }

    @Override
    public Document get(String chunkId) {
        if (chunkId == null) {
            return null;
        }
        ensureInitialized();
        List<Document> results = jdbcTemplate.query(SELECT_SQL.formatted(tableName), ROW_MAPPER, chunkId);
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public void deleteByDocumentId(String documentId) {
        if (documentId == null) {
            return;
        }
        ensureInitialized();
        jdbcTemplate.update(DELETE_BY_DOCUMENT_SQL.formatted(tableName), documentId);
    }

    private static String toJson(Map<String, Object> metadata) {
        try {
            return MAPPER.writeValueAsString(metadata);
        } catch (Exception e) {
            throw new RagException(RagErrorCode.STORE_WRITE_FAILED, "父块 metadata 序列化失败", e);
        }
    }

    private static final RowMapper<Document> ROW_MAPPER = (rs, rowNum) -> {
        String chunkId = rs.getString("chunk_id");
        String text = rs.getString("text");
        String metadataJson = rs.getString("metadata");
        return Document.builder()
                .id(chunkId)
                .text(text)
                .metadata(parseMetadata(metadataJson))
                .build();
    };

    private static Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RagException(RagErrorCode.STORE_READ_FAILED, "父块 metadata 反序列化失败", e);
        }
    }
}
