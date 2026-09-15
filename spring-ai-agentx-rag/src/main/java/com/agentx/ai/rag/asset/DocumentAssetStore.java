package com.agentx.ai.rag.asset;

/**
 * 文档衍生资产存储 SPI。
 *
 * <p>解析器只依赖该接口，不感知 MinIO、S3 或本地磁盘等具体存储实现。
 *
 * @author bigchui
 */
public interface DocumentAssetStore {

    /**
     * 保存资产并返回可长期引用的 URI。
     *
     * @param documentId 所属文档 ID
     * @param assetName  资产相对名称，例如 images/example.jpg
     * @param content    资产字节
     * @param mediaType  资产 MIME 类型
     * @return 已保存资产
     */
    DocumentAsset save(String documentId, String assetName, byte[] content, String mediaType);
}
