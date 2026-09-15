package com.agentx.ai.rag.exception;

/**
 * RAG 模块统一错误码。
 *
 * <p>错误码段使用 E4xxx，与 core 模块的 E1xxx、E2xxx、E3xxx 保持分区，
 * 调用方可根据错误码做精准降级或告警。
 *
 * @author bigchui
 */
public enum RagErrorCode {

    /**
     * 文档解析失败。
     */
    DOCUMENT_PARSE_FAILED("E4001"),

    /**
     * 不支持的文档类型。
     */
    UNSUPPORTED_DOCUMENT_TYPE("E4002"),

    /**
     * 文档读取失败。
     */
    DOCUMENT_READ_FAILED("E4003"),

    /**
     * 图片语义理解失败。
     */
    IMAGE_UNDERSTANDING_FAILED("E4004"),

    /**
     * 解析器配置非法。
     */
    PARSER_CONFIG_INVALID("E4100"),

    /**
     * 分块参数非法。
     */
    SPLIT_CONFIG_INVALID("E4101"),

    /**
     * 分块执行失败。
     */
    SPLIT_EXECUTION_FAILED("E4102"),

    /**
     * 存储配置非法。
     */
    STORE_CONFIG_INVALID("E4200"),

    /**
     * 存储写入失败。
     */
    STORE_WRITE_FAILED("E4201"),

    /**
     * 存储读取失败。
     */
    STORE_READ_FAILED("E4202"),

    /**
     * 检索执行失败。
     */
    RETRIEVE_FAILED("E4300"),

    /**
     * 检索编排配置非法。
     */
    PIPELINE_CONFIG_INVALID("E4301"),

    /**
     * 检索器配置非法。
     */
    RETRIEVER_CONFIG_INVALID("E4302");

    private final String code;

    RagErrorCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
