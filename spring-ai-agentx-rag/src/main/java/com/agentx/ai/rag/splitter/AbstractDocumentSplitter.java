package com.agentx.ai.rag.splitter;

import com.agentx.ai.rag.exception.RagErrorCode;
import com.agentx.ai.rag.exception.RagException;
import com.agentx.ai.rag.common.ChunkIdGenerator;
import com.agentx.ai.rag.common.ContentType;
import com.agentx.ai.rag.common.ImageReferences;
import com.agentx.ai.rag.common.MetadataKeys;
import com.agentx.ai.rag.common.TableReferences;
import com.agentx.ai.rag.common.UuidChunkIdGenerator;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 基于 Spring AI {@link TextSplitter} 的分块器模板基类。
 *
 * <p>Spring AI 负责文本切分和原文档 metadata 透传；本类统一写入 RAG metadata
 * 契约中的 chunkId、chunkGroupId、chunkIndex 和 chunkTotal，避免同时暴露
 * Spring AI 的内部切分字段和 AgentX 的检索字段。
 *
 * @author bigchui
 */
public abstract class AbstractDocumentSplitter extends TextSplitter {

    public static final int DEFAULT_CHUNK_SIZE = 512;
    public static final int DEFAULT_OVERLAP = 64;

    private static final String SPRING_CHUNK_INDEX = "chunk_index";
    private static final String SPRING_CHUNK_TOTAL = "total_chunks";
    private static final String SPRING_PARENT_DOCUMENT_ID = "parent_document_id";

    private final int chunkSize;
    private final int overlap;
    private final boolean preserveImageRef;
    private final boolean preserveTableRef;
    private final ChunkIdGenerator chunkIdGenerator;

    protected AbstractDocumentSplitter(int chunkSize, int overlap, ChunkIdGenerator chunkIdGenerator,
                                       boolean preserveImageRef, boolean preserveTableRef) {
        if (chunkSize <= 0) {
            throw new RagException(RagErrorCode.SPLIT_CONFIG_INVALID,
                    "chunkSize 必须大于 0: " + chunkSize);
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new RagException(RagErrorCode.SPLIT_CONFIG_INVALID,
                    "overlap 必须大于等于 0 且小于 chunkSize: " + overlap);
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.preserveImageRef = preserveImageRef;
        this.preserveTableRef = preserveTableRef;
        this.chunkIdGenerator = chunkIdGenerator == null
                ? new UuidChunkIdGenerator()
                : chunkIdGenerator;
    }

    @Override
    public List<Document> split(Document document) {
        Objects.requireNonNull(document, "document");
        if (document.getText() == null || document.getText().isBlank()) {
            return List.of();
        }

        Document prepared = prepareDocument(document);
        List<Document> chunks = super.split(prepared);
        return finalizeChunks(chunks, prepared);
    }

    @Override
    public List<Document> split(List<Document> documents) {
        if (documents == null) {
            return List.of();
        }
        List<Document> result = new ArrayList<>();
        for (Document document : documents) {
            if (document != null) {
                result.addAll(split(document));
            }
        }
        return result;
    }

    protected final int chunkSize() {
        return chunkSize;
    }

    protected final int overlap() {
        return overlap;
    }

    protected final boolean preserveImageRef() {
        return preserveImageRef;
    }

    protected final boolean preserveTableRef() {
        return preserveTableRef;
    }

    protected final ChunkIdGenerator chunkIdGenerator() {
        return chunkIdGenerator;
    }

    /**
     * 准备待切分文档，统一补齐 documentId、fileName、contentType。
     */
    protected final Document prepareDocument(Document source) {
        Map<String, Object> metadata = copyMetadata(source.getMetadata());
        clearChunkLifecycleMetadata(metadata);
        ensureSourceMetadata(metadata, source);
        metadata.putIfAbsent(MetadataKeys.CONTENT_TYPE, ContentType.TEXT.code());

        Document.Builder builder = Document.builder()
                .id(source.getId())
                .text(source.getText())
                .metadata(metadata);
        if (source.getScore() != null) {
            builder.score(source.getScore());
        }
        return builder.build();
    }

    protected final Document buildDocument(String text, String chunkId,
                                           Map<String, Object> metadata, Double score) {
        Document.Builder builder = Document.builder()
                .id(chunkId)
                .text(text)
                .metadata(metadata);
        if (score != null) {
            builder.score(score);
        }
        return builder.build();
    }

    protected final Map<String, Object> copyMetadata(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    protected static void clearChunkLifecycleMetadata(Map<String, Object> metadata) {
        metadata.remove(MetadataKeys.CHUNK_ID);
        metadata.remove(MetadataKeys.PARENT_CHUNK_ID);
        metadata.remove(MetadataKeys.CHUNK_ROLE);
        metadata.remove(MetadataKeys.CHUNK_GROUP_ID);
        metadata.remove(MetadataKeys.CHUNK_INDEX);
        metadata.remove(MetadataKeys.CHUNK_TOTAL);
        metadata.remove(MetadataKeys.SKIP_EMBEDDING);
        metadata.remove(SPRING_CHUNK_INDEX);
        metadata.remove(SPRING_CHUNK_TOTAL);
        metadata.remove(SPRING_PARENT_DOCUMENT_ID);
    }

    /**
     * 创建内容保护器，统一管理图片引用和表格的原子性保护。
     */
    protected final ContentProtector createProtector(String text) {
        return ContentProtector.protect(text, preserveImageRef, preserveTableRef);
    }

    /**
     * 按 code point 计算长度并按固定窗口切分，支持图片和表格保护。
     */
    protected static List<String> splitFixedWindow(String text, int chunkSize, int overlap,
                                                   boolean preserveImageRef, boolean preserveTableRef) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        ContentProtector protector = ContentProtector.protect(text, preserveImageRef, preserveTableRef);
        if (protector.hasProtectedContent()) {
            return splitCodePoints(protector.protectedText(), chunkSize, overlap).stream()
                    .map(protector::restore)
                    .toList();
        }
        return splitCodePoints(text, chunkSize, overlap);
    }

    private static List<String> splitCodePoints(String text, int chunkSize, int overlap) {
        int[] codePoints = text.codePoints().toArray();
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < codePoints.length) {
            int end = Math.min(codePoints.length, start + chunkSize);
            chunks.add(new String(codePoints, start, end - start));
            if (end >= codePoints.length) {
                break;
            }
            int next = end - overlap;
            if (next <= start) {
                next = start + 1;
            }
            start = next;
        }
        return chunks;
    }

    private List<Document> finalizeChunks(List<Document> chunks, Document source) {
        String fileName = asString(source.getMetadata().get(MetadataKeys.FILE_NAME));
        String chunkGroupId = chunkIdGenerator.nextId();
        int chunkTotal = chunks.size();
        List<Document> result = new ArrayList<>(chunks.size());

        int chunkIndex = 0;
        for (Document chunk : chunks) {
            Map<String, Object> metadata = new LinkedHashMap<>(chunk.getMetadata());
            String chunkId = chunkIdGenerator.nextId();

            metadata.put(MetadataKeys.CHUNK_ID, chunkId);
            metadata.putIfAbsent(MetadataKeys.FILE_NAME, fileName);
            metadata.putIfAbsent(MetadataKeys.CONTENT_TYPE, ContentType.TEXT.code());
            metadata.put(MetadataKeys.CHUNK_GROUP_ID, chunkGroupId);
            metadata.put(MetadataKeys.CHUNK_INDEX, chunkIndex++);
            metadata.put(MetadataKeys.CHUNK_TOTAL, chunkTotal);
            applyContentMetadata(metadata, chunk.getText(), chunkSize);

            metadata.remove(SPRING_CHUNK_INDEX);
            metadata.remove(SPRING_CHUNK_TOTAL);
            metadata.remove(SPRING_PARENT_DOCUMENT_ID);

            result.add(buildDocument(chunk.getText(), chunkId, metadata, chunk.getScore()));
        }
        return result;
    }

    /**
     * 写入图片和表格相关 metadata。
     */
    protected static void applyContentMetadata(Map<String, Object> metadata, String text, int chunkSize) {
        applyImageMetadata(metadata, text, chunkSize);
        applyTableMetadata(metadata, text, chunkSize);
    }

    protected static void applyImageMetadata(Map<String, Object> metadata, String text, int chunkSize) {
        List<ImageReferences.ImageReference> references = ImageReferences.find(text);
        if (references.isEmpty()) {
            return;
        }
        metadata.put(MetadataKeys.CONTAINS_IMG, true);
        metadata.put(MetadataKeys.IMG_URLS, references.stream()
                .map(ImageReferences.ImageReference::uri)
                .toList());
        metadata.put(MetadataKeys.OVERSIZED_IMG_REF, references.stream()
                .anyMatch(reference -> reference.token()
                        .codePointCount(0, reference.token().length()) > chunkSize));
    }

    protected static void applyTableMetadata(Map<String, Object> metadata, String text, int chunkSize) {
        List<TableReferences.TableReference> tables = TableReferences.find(text);
        if (tables.isEmpty()) {
            return;
        }
        metadata.put(MetadataKeys.CONTAINS_TABLE, true);
        metadata.put(MetadataKeys.TABLE_COUNT, tables.size());
        metadata.put(MetadataKeys.OVERSIZED_TABLE_REF, tables.stream()
                .anyMatch(table -> table.token()
                        .codePointCount(0, table.token().length()) > chunkSize));
    }

    private void ensureSourceMetadata(Map<String, Object> metadata, Document source) {
        String fileName = asString(metadata.get(MetadataKeys.FILE_NAME));
        if (isBlank(fileName)) {
            fileName = isBlank(source.getId()) ? chunkIdGenerator.nextId() : source.getId();
        }
        metadata.put(MetadataKeys.FILE_NAME, fileName);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
