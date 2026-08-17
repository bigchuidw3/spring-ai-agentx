package com.agentx.ai.core.exception;

/**
 * Agent 异常错误码。
 *
 * @author bigchui
 *
 */
public enum AgentErrorCode {

    /**
     * LLM 调用失败（已耗尽重试次数）
     */
    LLM_CALL_FAILED("E1001"),

    /**
     * LLM 返回空响应
     */
    LLM_EMPTY_RESPONSE("E1002"),

    /**
     * 同一会话存在并发执行
     */
    CONCURRENT_EXECUTION("E2001"),

    /**
     * 沙箱镜像拉取失败
     */
    SANDBOX_IMAGE_PULL_FAILED("E3001"),

    /**
     * 沙箱命令执行超时
     */
    SANDBOX_EXEC_TIMEOUT("E3002"),

    /**
     * 沙箱容器不存在或未运行
     */
    SANDBOX_CONTAINER_NOT_FOUND("E3003");


    private final String code;

    AgentErrorCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
