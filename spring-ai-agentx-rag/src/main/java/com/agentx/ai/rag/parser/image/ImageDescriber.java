package com.agentx.ai.rag.parser.image;

/**
 * 图片语义理解 SPI。
 *
 * @author bigchui
 */
public interface ImageDescriber {

    ImageDescription describe(ImageDescriptionRequest request);
}
