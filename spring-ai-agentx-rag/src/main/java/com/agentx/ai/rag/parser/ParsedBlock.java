package com.agentx.ai.rag.parser;

import com.agentx.ai.rag.common.ContentType;

import java.util.Map;
import java.util.Objects;

/**
 * 解析器产出的单个内容块。
 *
 * <p>文本块和表格块以 {@link #text()} 为主要载体，图片块可同时携带二进制内容
 * 和可选描述文本。
 *
 * @author bigchui
 */
public record ParsedBlock(ContentType type, String text, byte[] binary, Map<String, Object> metadata) {

    public ParsedBlock {
        Objects.requireNonNull(type, "type");
        text = text == null ? "" : text;
        binary = binary == null ? null : binary.clone();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    @Override
    public byte[] binary() {
        return binary == null ? null : binary.clone();
    }

    public static ParsedBlock text(String text) {
        return new ParsedBlock(ContentType.TEXT, text, null, Map.of());
    }

    public static ParsedBlock table(String text) {
        return new ParsedBlock(ContentType.TABLE, text, null, Map.of());
    }

    public static ParsedBlock image(byte[] binary, String description) {
        return new ParsedBlock(ContentType.IMAGE, description, binary, Map.of());
    }
}
