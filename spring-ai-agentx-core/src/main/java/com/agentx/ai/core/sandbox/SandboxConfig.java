package com.agentx.ai.core.sandbox;

import com.agentx.ai.core.sandbox.snapshot.LocalSnapshotStorage;
import com.agentx.ai.core.sandbox.snapshot.SnapshotMetadataStore;
import com.agentx.ai.core.sandbox.snapshot.SnapshotStorage;

import java.util.Objects;

/**
 * 沙箱通用配置。
 *
 * <p>只承载与具体后端无关的参数；镜像、资源限制、容器工作目录等后端专属配置
 * 在后端实现类中设置，通过 {@link Builder#backend(SandboxBackend)} 显式传入：
 *
 * <pre>{@code
 * // Docker 模式
 * SandboxConfig.builder()
 *     .backend(DockerBackend.builder()
 *             .image("ubuntu:22.04")
 *             .memoryMb(1024)
 *             .networkDisabled(false)
 *             .build())
 *     .isolationScope(IsolationScope.CONVERSATION)
 *     .snapshotDir(Path.of("/data/agentx-snapshots"))
 *     .build();
 *
 * // 本地模式（开发调试，无需 Docker）
 * SandboxConfig.builder()
 *     .backend(new LocalExecutionBackend("/data/sandbox-ws"))
 *     .isolationScope(IsolationScope.CONVERSATION)
 *     .build();
 * }</pre>
 *
 * <p>快照存储：使用本地 tar 存储（默认）时必须显式配置 {@code snapshotDir}，
 * 生产环境请指定稳定目录；多实例部署应注入自定义 {@link SnapshotStorage}
 * （如 MinIO 实现），此时无需 {@code snapshotDir}。
 *
 * @author bigchui
 */
public class SandboxConfig {

    private final SandboxBackend backend;
    private final IsolationScope isolationScope;
    private final WorkspaceSpec workspaceSpec;
    private final boolean strictMode;
    private final java.nio.file.Path snapshotDir;
    private final SnapshotStorage snapshotStorage;
    private final SnapshotMetadataStore snapshotMetadataStore;

    private SandboxConfig(Builder b) {
        this.backend = b.backend;
        this.isolationScope = b.isolationScope;
        this.workspaceSpec = b.workspaceSpec;
        this.strictMode = b.strictMode;
        this.snapshotDir = b.snapshotDir;
        this.snapshotStorage = b.snapshotStorage;
        this.snapshotMetadataStore = b.snapshotMetadataStore;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 沙箱后端（DockerBackend / LocalExecutionBackend 或自定义实现）。
     */
    public SandboxBackend getBackend() {
        return backend;
    }

    public IsolationScope getIsolationScope() {
        return isolationScope;
    }

    public WorkspaceSpec getWorkspaceSpec() {
        return workspaceSpec;
    }

    /**
     * 是否启用严格模式。
     *
     * <p>严格模式下沙箱不可用或获取失败时，工具调用直接返回错误，
     * 绝不降级到宿主机执行；默认 {@code false}（失败降级宿主执行并打日志）。
     */
    public boolean isStrictMode() {
        return strictMode;
    }

    /**
     * 本地快照目录（使用内置 LocalSnapshotStorage 时生效）。
     */
    public java.nio.file.Path getSnapshotDir() {
        return snapshotDir;
    }

    public SnapshotStorage getSnapshotStorage() {
        return snapshotStorage;
    }

    public SnapshotMetadataStore getSnapshotMetadataStore() {
        return snapshotMetadataStore;
    }

    public static class Builder {

        private SandboxBackend backend;
        private IsolationScope isolationScope = IsolationScope.CONVERSATION;
        private WorkspaceSpec workspaceSpec = WorkspaceSpec.empty();
        private boolean strictMode = false;
        private java.nio.file.Path snapshotDir;
        private SnapshotStorage snapshotStorage;
        private SnapshotMetadataStore snapshotMetadataStore;

        /**
         * 沙箱后端（必填）。
         *
         * @param backend {@code DockerBackend.builder().build()} 或 {@code new LocalExecutionBackend(dir)}
         */
        public Builder backend(SandboxBackend backend) {
            this.backend = Objects.requireNonNull(backend, "backend must not be null");
            return this;
        }

        public Builder isolationScope(IsolationScope scope) {
            this.isolationScope = Objects.requireNonNull(scope, "isolationScope must not be null");
            return this;
        }

        public Builder workspaceSpec(WorkspaceSpec spec) {
            this.workspaceSpec = Objects.requireNonNull(spec, "workspaceSpec must not be null");
            return this;
        }

        /**
         * 严格模式：沙箱失败时工具调用报错，不降级到宿主机执行。
         */
        public Builder strictMode(boolean strict) {
            this.strictMode = strict;
            return this;
        }

        /**
         * 本地快照目录。后端需要快照且未注入自定义 {@link SnapshotStorage} 时必填。
         */
        public Builder snapshotDir(java.nio.file.Path dir) {
            this.snapshotDir = dir;
            return this;
        }

        /**
         * {@link #snapshotDir(Path)} 的字符串便捷重载，适合从配置文件取值的场景。
         */
        public Builder snapshotDir(String dir) {
            this.snapshotDir = java.nio.file.Path.of(
                    Objects.requireNonNull(dir, "dir must not be null"));
            return this;
        }

        /**
         * 注入自定义快照存储（如 MinioSnapshotStorage）。
         * <p>
         * 不传则使用框架内置的 {@link LocalSnapshotStorage}（本地磁盘，单机场景）。
         *
         * @param snapshotStorage 快照存储实现
         * @return this
         */
        public Builder snapshotStorage(SnapshotStorage snapshotStorage) {
            this.snapshotStorage = snapshotStorage;
            return this;
        }

        /**
         * 注入自定义快照元数据存储。
         * <p>
         * 不传则框架根据是否有 DataSource 自动选择 JDBC 或内存实现。
         *
         * @param snapshotMetadataStore 快照元数据存储实现
         * @return this
         */
        public Builder snapshotMetadataStore(SnapshotMetadataStore snapshotMetadataStore) {
            this.snapshotMetadataStore = snapshotMetadataStore;
            return this;
        }

        public SandboxConfig build() {
            if (backend == null) {
                throw new IllegalArgumentException(
                        "必须指定沙箱后端：.backend(DockerBackend.builder().build()) 或 .backend(new LocalExecutionBackend(dir))");
            }
            if (snapshotStorage == null && snapshotDir == null && backend.supportsSnapshot()) {
                throw new IllegalArgumentException(
                        "使用本地快照存储必须显式配置 snapshotDir（生产环境请指定稳定目录），"
                                + "或通过 .snapshotStorage(...) 注入自定义实现（如 MinIO）");
            }
            return new SandboxConfig(this);
        }
    }
}
