package com.agentx.ai.rag.asset;

import com.agentx.ai.rag.exception.RagErrorCode;
import com.agentx.ai.rag.exception.RagException;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Objects;

/**
 * 基于 MinIO 的文档资产存储。
 *
 * 图片等资产上传到 MinIO，返回可公开访问的 HTTP URI，供 Markdown 图片引用。
 *
 * @author bigchui
 */
public final class MinioDocumentAssetStore implements DocumentAssetStore {

    private final MinioClient minioClient;
    private final String bucketName;
    private final String publicBaseUrl;

    public MinioDocumentAssetStore(MinioClient minioClient, String bucketName, String publicBaseUrl) {
        this.minioClient = Objects.requireNonNull(minioClient, "minioClient");
        this.bucketName = Objects.requireNonNull(bucketName, "bucketName");
        this.publicBaseUrl = normalizeBaseUrl(publicBaseUrl);
        ensureBucket();
    }

    @Override
    public DocumentAsset save(String documentId, String assetName, byte[] content, String mediaType) {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(assetName, "assetName");
        Objects.requireNonNull(content, "content");

        String objectName = documentId + "/" + assetName;
        try (InputStream stream = new ByteArrayInputStream(content)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .stream(stream, content.length, -1)
                    .contentType(mediaType == null ? "application/octet-stream" : mediaType)
                    .build());
        } catch (Exception e) {
            throw new RagException(RagErrorCode.DOCUMENT_PARSE_FAILED,
                    "资产上传 MinIO 失败: " + objectName, e);
        }

        return new DocumentAsset(
                objectName,
                publicBaseUrl + "/" + bucketName + "/" + objectName,
                mediaType == null ? "" : mediaType,
                content.length);
    }

    private void ensureBucket() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            }
            String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Principal\":{\"AWS\":[\"*\"]},\"Action\":[\"s3:GetObject\"],"
                    + "\"Resource\":[\"arn:aws:s3:::" + bucketName + "/*\"]}]}";
            minioClient.setBucketPolicy(SetBucketPolicyArgs.builder()
                    .bucket(bucketName)
                    .config(policy)
                    .build());
        } catch (Exception e) {
            throw new RagException(RagErrorCode.DOCUMENT_PARSE_FAILED,
                    "MinIO bucket 初始化失败: " + bucketName, e);
        }
    }

    private static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("publicBaseUrl 不能为空");
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
