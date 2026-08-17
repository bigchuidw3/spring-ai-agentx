package com.agentx.ai.core.sandbox;

import com.agentx.ai.core.sandbox.snapshot.SnapshotMetadataRecord;
import com.agentx.ai.core.sandbox.snapshot.SnapshotMetadataStore;
import com.agentx.ai.core.sandbox.snapshot.SnapshotStorage;
import com.agentx.ai.core.stage.AgentRuntimeContext;
import com.agentx.ai.core.model.RunnableParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.time.Instant;

/**
 * 沙箱生命周期管理器。
 *
 * <p>编排 {@link SandboxBackend}、{@link SnapshotStorage} 与 {@link SnapshotMetadataStore}，
 * 不感知任何 docker 细节。由 {@code SandboxHook} 在 BeforeCall / AfterCall 时调用。
 *
 * <p>acquire 优先级：
 * <ol>
 *   <li>复用已运行容器（pause/resume 场景）</li>
 *   <li>从快照恢复</li>
 *   <li>全新创建</li>
 * </ol>
 *
 * <p>release 流程：导出 workspace 为 tar 流 → {@link SnapshotStorage} 存 tar 包 →
 * {@link SnapshotMetadataStore} 写元数据（覆盖写）→ 销毁容器。
 *
 * <p>两层职责分离：SnapshotStorage 管 tar 字节流在哪（本地/MinIO），MetadataStore 管元数据（表），
 * 不管底层存储介质如何，元数据都写表，行为统一。
 *
 * @author bigchui
 */
public class SandboxManager {

    private static final Logger log = LoggerFactory.getLogger(SandboxManager.class);

    private final SandboxConfig config;
    private final SandboxBackend backend;
    private final SnapshotStorage snapshotStorage;
    private final SnapshotMetadataStore metadataStore;

    public SandboxManager(SandboxConfig config, SandboxBackend backend,
                          SnapshotStorage snapshotStorage,
                          SnapshotMetadataStore metadataStore) {
        this.config = config;
        this.backend = backend;
        this.snapshotStorage = snapshotStorage;
        this.metadataStore = metadataStore;
    }

    /**
     * 获取沙箱实例（复用 / 恢复 / 新建）。
     *
     * @param ctx 运行时上下文（取 conversationId / userId 计算 scope key）
     * @return 沙箱实例
     * @throws Exception 获取失败
     */
    public Sandbox acquire(AgentRuntimeContext ctx) throws Exception {
        String scopeKey = computeScopeKey(ctx);
        String containerName = com.agentx.ai.core.sandbox.docker.ContainerNameUtil.toContainerName(scopeKey);

        // 1. 复用已运行容器（resume 场景）
        Sandbox existing = backend.findExisting(containerName);
        if (existing != null) {
            log.info("[SandboxManager] 复用已运行容器: {}", containerName);
            return existing;
        }

        // 2. 从快照恢复（元数据记录存在 + tar 包存在，双重确认）
        if (metadataStore.find(scopeKey).isPresent() && snapshotStorage.exists(scopeKey)) {
            try (InputStream tar = snapshotStorage.load(scopeKey)) {
                log.info("[SandboxManager] 从快照恢复: {}", containerName);
                return backend.restore(config.getWorkspaceSpec(), containerName, tar);
            } catch (Exception e) {
                log.warn("[SandboxManager] 快照恢复失败，将全新创建: {}", containerName, e);
            }
        }

        // 3. 全新创建
        log.info("[SandboxManager] 创建新容器: {}", containerName);
        return backend.createSandbox(config.getWorkspaceSpec(), containerName);
    }

    /**
     * 释放沙箱（快照 + 销毁）。
     *
     * <p>快照成功（或后端不需要快照）才销毁容器；快照失败时保留容器，
     * 供下次 acquire 的 findExisting 复用，避免 workspace 状态丢失。
     *
     * @param sandbox 沙箱实例
     * @param ctx     运行时上下文
     */
    public void release(Sandbox sandbox, AgentRuntimeContext ctx) {
        String scopeKey = computeScopeKey(ctx);
        try {
            InputStream tar = backend.exportWorkspace(sandbox);
            if (tar != null) {
                long size;
                try (tar) {
                    size = snapshotStorage.save(scopeKey, tar);
                }
                metadataStore.save(new SnapshotMetadataRecord(
                        scopeKey,
                        config.getIsolationScope(),
                        sandbox.getContainerName(),
                        size,
                        Instant.now()
                ));
                log.info("[SandboxManager] 快照已保存: {} ({} bytes)", sandbox.getContainerName(), size);
            } else {
                log.debug("[SandboxManager] 后端无需快照，跳过: {}", sandbox.getContainerName());
            }
        } catch (Exception e) {
            log.error("[SandboxManager] 快照保存失败，保留容器 {} 待下次复用",
                    sandbox.getContainerName(), e);
            return;
        }
        try {
            backend.destroy(sandbox);
        } catch (Exception e) {
            log.error("[SandboxManager] 容器销毁失败: {}", sandbox.getContainerName(), e);
        }
    }

    /**
     * 根据 {@link IsolationScope} 计算 scope key。
     */
    private String computeScopeKey(AgentRuntimeContext ctx) {
        RunnableParams params = ctx.getParams();
        if (config.getIsolationScope() == IsolationScope.USER) {
            String userId = params != null ? params.getUserId() : null;
            if (userId != null && !userId.isBlank()) {
                return userId;
            }
        }
        String conversationId = params != null ? params.getConversationId() : null;
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalStateException("无法计算 scope key: conversationId 和 userId 均为空");
        }
        return conversationId;
    }

    public SandboxConfig getConfig() {
        return config;
    }

    public SandboxBackend getBackend() {
        return backend;
    }
}
