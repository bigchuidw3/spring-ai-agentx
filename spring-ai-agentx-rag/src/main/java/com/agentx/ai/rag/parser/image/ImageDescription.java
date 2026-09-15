package com.agentx.ai.rag.parser.image;

/**
 * 图片语义理解结果。
 *
 * @param text      图片语义描述
 * @param modelName 生成描述的模型名称
 * @author bigchui
 */
public record ImageDescription(String text, String modelName) {
}
