package com.agentx.ai.rag.parser;

import com.agentx.ai.rag.common.MetadataKeys;
import com.agentx.ai.rag.reader.RawDocument;
import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 文档解析 SPI。
 *
 * <p>解析器只负责把原始字节归一化成结构化内容块，不负责分块。
 * 框架不自动识别文件格式，由调用方按场景选择合适的解析器。
 *
 * @author bigchui
 */
public interface DocumentParser {

    /**
     * 判断当前解析器是否支持指定文件。
     *
     * @param fileName 文件名或文件名扩展名
     * @return 支持时返回 true
     */
    boolean supports(String fileName);

    /**
     * 解析原始文件内容。
     *
     * @param content  原始字节
     * @param fileName 文件名，用于内容类型识别和来源追溯
     * @return 结构化解析结果
     */
    ParsedDocument parse(byte[] content, String fileName);

    /**
     * 解析 Reader 输出的原始文档，并保留来源 metadata。
     *
     * @param rawDocument 原始文档
     * @return 结构化解析结果
     */
    default ParsedDocument parse(RawDocument rawDocument) {
        Objects.requireNonNull(rawDocument, "rawDocument");
        ParsedDocument parsed = parse(rawDocument.content(), rawDocument.fileName());

        Map<String, Object> metadata = new LinkedHashMap<>(rawDocument.metadata());
        metadata.putIfAbsent(MetadataKeys.FILE_NAME, rawDocument.fileName());
        metadata.putAll(parsed.metadata());
        return new ParsedDocument(
                parsed.fileName(),
                parsed.blocks(),
                metadata
        );
    }

    /**
     * 解析原始文档并直接转换为 Spring AI Document，调用方可将结果传给分块器。
     *
     * @param rawDocument 原始文档
     * @return 可分块的 Spring AI 文档列表
     */
    default List<Document> parseToDocuments(RawDocument rawDocument) {
        return new ParsedDocumentMapper().toDocuments(parse(rawDocument));
    }
}
