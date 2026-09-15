package com.agentx.ai.rag.parser;

import java.util.List;
import java.util.Map;

/**
 * 文档解析结果。
 *
 * @author bigchui
 */
public record ParsedDocument(
        String fileName,
        List<ParsedBlock> blocks,
        Map<String, Object> metadata) {

    public ParsedDocument {
        fileName = fileName == null ? "" : fileName;
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
