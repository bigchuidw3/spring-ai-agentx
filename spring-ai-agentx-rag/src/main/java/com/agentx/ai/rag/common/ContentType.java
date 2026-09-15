package com.agentx.ai.rag.common;

import com.agentx.ai.rag.exception.RagErrorCode;
import com.agentx.ai.rag.exception.RagException;

/**
 * RAG 内容块类型。
 *
 * @author bigchui
 */
public enum ContentType {

    TEXT("text"),
    TABLE("table"),
    IMAGE("image");

    private final String code;

    ContentType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ContentType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return TEXT;
        }
        for (ContentType value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        throw new RagException(RagErrorCode.PARSER_CONFIG_INVALID, "未知内容类型: " + code);
    }
}
