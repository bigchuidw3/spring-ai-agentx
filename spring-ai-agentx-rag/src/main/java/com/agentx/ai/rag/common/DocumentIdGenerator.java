package com.agentx.ai.rag.common;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * 文档唯一标识生成工具。
 *
 * 不传内容时生成随机 id，传内容时基于 SHA-256 生成稳定 id（同一份内容得到同一个 id）。
 * 调用方在构建索引时用此生成 documentId，也可传自己的业务 id。
 *
 * @author bigchui
 */
public final class DocumentIdGenerator {

    private DocumentIdGenerator() {
    }

    public static String getDocId() {
        return UUID.randomUUID().toString();
    }

    public static String getDocId(byte[] content) {
        Objects.requireNonNull(content, "content");
        if (content.length == 0) {
            throw new IllegalArgumentException("content 不能为空");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
