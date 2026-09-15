package com.agentx.ai.rag.asset;

import java.util.Objects;

/**
 * 已保存的文档衍生资产。
 *
 * <p>由 {@link DocumentAssetStore#save} 产出，携带稳定 URI 供后续引用。
 *
 * @author bigchui
 */
public record DocumentAsset(
        String assetId,
        String uri,
        String mediaType,
        long size) {

    public DocumentAsset {
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(uri, "uri");
        mediaType = mediaType == null ? "" : mediaType;
    }
}