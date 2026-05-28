package com.agentx.ai.core.exception;

/**
 * Agent 异常错误码。
 *
 * @author bigchui
 * 
 */
public enum AgentErrorCode {

    /** LLM 调用失败（已耗尽重试次数） */
    LLM_CALL_FAILED("E1001"),

    /** LLM 返回空响应 */
    LLM_EMPTY_RESPONSE("E1002"),

    /** 同一会话存在并发执行 */
    CONCURRENT_EXECUTION("E2001");



    private final String code;

    AgentErrorCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
