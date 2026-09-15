package com.agentx.ai.rag.reader;

import java.util.Map;
import java.util.Objects;

/**
 * 待解析的原始文档。
 *
 * <p>Reader 只负责获取原始字节和来源信息，不理解文件格式，也不做内容解析。
 *
 * @author bigchui
 */
public record RawDocument(String fileName, byte[] content, Map<String, Object> metadata) {

    public RawDocument {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(content, "content");
        content = content.clone();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
