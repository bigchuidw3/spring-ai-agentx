package com.agentx.ai.rag.exception;

/**
 * RAG 模块统一异常。
 *
 * <p>RAG 模块不依赖 core，使用独立的 {@link RagErrorCode} 表达错误类型。
 *
 * @author bigchui
 */
public class RagException extends RuntimeException {

    private final RagErrorCode code;

    public RagException(RagErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public RagException(RagErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public RagErrorCode getCode() {
        return code;
    }

    @Override
    public String toString() {
        return "RagException{" + code.code() + " " + code.name() + ": " + getMessage() + '}';
    }
}
