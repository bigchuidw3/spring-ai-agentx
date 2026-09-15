package com.agentx.ai.rag.parser;

import com.agentx.ai.rag.common.ChunkIdGenerator;
import com.agentx.ai.rag.common.ContentType;
import com.agentx.ai.rag.common.MetadataKeys;
import com.agentx.ai.rag.common.UuidChunkIdGenerator;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 将 {@link ParsedDocument} 转换为 Spring AI {@link Document} 列表。
 *
 * <p>解析器与分块器之间保持解耦，分块器只面向 Spring AI Document 工作。
 *
 * @author bigchui
 */
public final class ParsedDocumentMapper {

    private final ChunkIdGenerator chunkIdGenerator;

    public ParsedDocumentMapper() {
        this(new UuidChunkIdGenerator());
    }

    public ParsedDocumentMapper(ChunkIdGenerator chunkIdGenerator) {
        this.chunkIdGenerator = Objects.requireNonNull(chunkIdGenerator, "chunkIdGenerator");
    }

    /**
     * 将解析结果转换为待分块的 Spring AI 文档。
     *
     * @param parsed 解析结果
     * @return Spring AI 文档列表
     */
    public List<Document> toDocuments(ParsedDocument parsed) {
        Objects.requireNonNull(parsed, "parsed");

        Map<String, Object> base = new LinkedHashMap<>(parsed.metadata());
        base.putIfAbsent(MetadataKeys.FILE_NAME, parsed.fileName());

        List<Document> documents = new ArrayList<>(parsed.blocks().size());
        for (ParsedBlock block : parsed.blocks()) {
            // 图片块是解析期内部载体，语义和稳定引用已写回原文 Markdown。
            if (block.type() == ContentType.IMAGE) {
                continue;
            }
            if (block.text() == null || block.text().isBlank()) {
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>(base);
            metadata.putAll(block.metadata());
            metadata.put(MetadataKeys.CONTENT_TYPE, block.type().code());

            documents.add(Document.builder()
                    .id(chunkIdGenerator.nextId())
                    .text(block.text())
                    .metadata(metadata)
                    .build());
        }
        return documents;
    }
}
