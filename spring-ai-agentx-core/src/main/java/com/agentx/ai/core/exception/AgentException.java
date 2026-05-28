package com.agentx.ai.core.exception;

/**
 * Agent 统一异常。
 * <p>
 * 框架内部所有异常统一包装为 {@link AgentException}，通过 {@link AgentErrorCode}
 * 区分异常类型，调用方可根据 code 做精准处理。
 *
 * @author bigchui
 * 
 */
public class AgentException extends RuntimeException {

    private final AgentErrorCode code;

    public AgentException(AgentErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public AgentException(AgentErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public AgentErrorCode getCode() {
        return code;
    }

    @Override
    public String toString() {
        return "AgentException{" + code.code() + " " + code.name() + ": " + getMessage() + '}';
    }
}
