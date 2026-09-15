package com.agentx.ai.rag.parser.image;

import java.util.Locale;

/**
 * 常见图片 MIME 类型解析工具。
 *
 * @author bigchui
 */
public final class ImageMediaTypes {

    private ImageMediaTypes() {
    }

    public static String fromFileName(String fileName) {
        String lowerFileName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lowerFileName.endsWith(".png")) {
            return "image/png";
        }
        if (lowerFileName.endsWith(".jpg") || lowerFileName.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lowerFileName.endsWith(".gif")) {
            return "image/gif";
        }
        if (lowerFileName.endsWith(".bmp")) {
            return "image/bmp";
        }
        if (lowerFileName.endsWith(".webp")) {
            return "image/webp";
        }
        if (lowerFileName.endsWith(".jp2")) {
            return "image/jp2";
        }
        return "application/octet-stream";
    }
}
