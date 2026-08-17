package com.agentx.ai.core.sandbox.snapshot;

import java.io.InputStream;

/**
 * 快照存储接口。
 *
 * <p>决定 workspace tar 包"存哪、从哪读"，是单机与多机场景的切换点：
 * <ul>
 *   <li>单机：框架内置 {@link LocalSnapshotStorage}，tar 落本地磁盘（默认）</li>
 *   <li>多机：调用方自行实现（如 MinioSnapshotStorage），通过
 *       {@link SandboxConfig.Builder#snapshotStorage(SnapshotStorage)} 注入</li>
 * </ul>
 *
 * <p>每个 snapshotId 只保留最新一份（覆盖写）。
 * 框架不内置任何第三方存储依赖（MinIO / OSS / S3 等），由调用方按需实现。
 *
 * @author bigchui
 */
public interface SnapshotStorage {

    /**
     * 保存 workspace tar 包（覆盖写：同一 snapshotId 只保留最新）。
     *
     * @param snapshotId 快照标识（通常为 scopeKey — conversationId 或 userId）
     * @param tarStream  workspace tar 流
     * @return 写入字节数
     * @throws Exception 存储失败
     */
    long save(String snapshotId, InputStream tarStream) throws Exception;

    /**
     * 读取 workspace tar 包。
     *
     * @param snapshotId 快照标识
     * @return tar 流（调用方负责关闭）
     * @throws Exception 读取失败或快照不存在
     */
    InputStream load(String snapshotId) throws Exception;

    /**
     * 快照是否存在。
     *
     * @param snapshotId 快照标识
     * @return 存在返回 {@code true}
     * @throws Exception 检查失败
     */
    boolean exists(String snapshotId) throws Exception;
}
