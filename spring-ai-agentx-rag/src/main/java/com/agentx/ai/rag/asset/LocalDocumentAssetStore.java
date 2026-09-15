package com.agentx.ai.rag.asset;

import com.agentx.ai.rag.exception.RagErrorCode;
import com.agentx.ai.rag.exception.RagException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * 本地文件系统资产存储。
 *
 * <p>将图片等资产保存到指定目录，返回 file URI 供引用。
 *
 * @author bigchui
 */
public final class LocalDocumentAssetStore implements DocumentAssetStore {

    private final Path baseDir;

    public LocalDocumentAssetStore(Path baseDir) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
    }

    @Override
    public DocumentAsset save(String documentId, String assetName, byte[] content, String mediaType) {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(assetName, "assetName");
        Objects.requireNonNull(content, "content");

        String assetId = documentId + "/" + assetName;
        Path targetFile = baseDir.resolve(documentId).resolve(assetName).normalize();
        if (!targetFile.startsWith(baseDir.normalize())) {
            throw new RagException(RagErrorCode.DOCUMENT_PARSE_FAILED,
                    "资产路径越界: " + assetName);
        }

        try {
            Files.createDirectories(targetFile.getParent());
            Files.write(targetFile, content);
        } catch (IOException e) {
            throw new RagException(RagErrorCode.DOCUMENT_PARSE_FAILED,
                    "资产保存失败: " + targetFile, e);
        }

        return new DocumentAsset(
                assetId,
                targetFile.toUri().toString(),
                mediaType == null ? "" : mediaType,
                content.length);
    }
}