package com.agentx.ai.rag.parser.mineru;

/**
 * MinerU 精准解析模型版本。
 *
 * @author bigchui
 */
public enum MineruModelVersion {

    PIPELINE("pipeline"),
    VLM("vlm"),
    HTML("MinerU-HTML");

    private final String code;

    MineruModelVersion(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
