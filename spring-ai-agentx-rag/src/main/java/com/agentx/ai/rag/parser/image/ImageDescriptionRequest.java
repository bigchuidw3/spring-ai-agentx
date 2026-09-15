package com.agentx.ai.rag.parser.image;

import java.util.Objects;

/**
 * 图片语义理解请求。
 *
 * @param documentFileName 所属文档文件名
 * @param imageFileName    图片文件名或路径
 * @param caption          解析器已识别的图片标题
 * @param mediaType        图片 MIME 类型
 * @param image            图片字节
 * @author bigchui
 */
public record ImageDescriptionRequest(
        String documentFileName,
        String imageFileName,
        String caption,
        String mediaType,
        byte[] image) {

    public ImageDescriptionRequest {
        documentFileName = Objects.requireNonNull(documentFileName, "documentFileName");
        imageFileName = Objects.requireNonNull(imageFileName, "imageFileName");
        caption = caption == null ? "" : caption;
        mediaType = Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(image, "image");
        image = image.clone();
    }

    @Override
    public byte[] image() {
        return image.clone();
    }
}
