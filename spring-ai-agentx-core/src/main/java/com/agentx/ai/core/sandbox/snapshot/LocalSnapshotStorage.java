package com.agentx.ai.core.sandbox.snapshot;

import com.agentx.ai.core.exception.AgentErrorCode;
import com.agentx.ai.core.sandbox.SandboxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 本地磁盘快照存储（框架内置默认实现）。
 *
 * <p>tar 文件存储在 {@code {baseDir}/{snapshotId}.tar}，采用原子写入：
 * 先写临时文件（{@code .{snapshotId}.{uuid}.tmp}），再 {@code ATOMIC_MOVE} 到目标路径。
 *
 * <p>单机场景开箱即用；多机场景调用方应自行实现 {@link SnapshotStorage}（如接入 MinIO）。
 *
 * @author bigchui
 */
public class LocalSnapshotStorage implements SnapshotStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalSnapshotStorage.class);

    private final Path baseDir;

    public LocalSnapshotStorage(Path baseDir) {
        this.baseDir = baseDir;
    }

    @Override
    public long save(String snapshotId, InputStream tarStream) throws Exception {
        validateId(snapshotId);
        Files.createDirectories(baseDir);
        Path target = baseDir.resolve(snapshotId + ".tar");
        Path tmp = baseDir.resolve("." + snapshotId + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.copy(tarStream, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            long size = Files.size(target);
            log.debug("[LocalSnapshotStorage] 快照已保存: {} ({} bytes)", target, size);
            return size;
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    @Override
    public InputStream load(String snapshotId) throws Exception {
        validateId(snapshotId);
        Path path = baseDir.resolve(snapshotId + ".tar");
        if (!Files.exists(path)) {
            throw new SandboxException(AgentErrorCode.SANDBOX_CONTAINER_NOT_FOUND,
                    "快照不存在: " + snapshotId);
        }
        return Files.newInputStream(path);
    }

    @Override
    public boolean exists(String snapshotId) {
        validateId(snapshotId);
        return Files.exists(baseDir.resolve(snapshotId + ".tar"));
    }

    /**
     * 防止路径穿越：snapshotId 只允许作为单层文件名。
     */
    private static void validateId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("snapshotId must not be null or blank");
        }
        if (id.contains("/") || id.contains("\\") || id.contains("..") || id.contains("\0")) {
            throw new IllegalArgumentException("snapshotId contains unsafe characters: " + id);
        }
    }
}
