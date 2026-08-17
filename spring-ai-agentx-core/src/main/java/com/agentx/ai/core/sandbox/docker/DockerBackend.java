package com.agentx.ai.core.sandbox.docker;

import com.agentx.ai.core.sandbox.Sandbox;
import com.agentx.ai.core.sandbox.SandboxBackend;
import com.agentx.ai.core.sandbox.WorkspaceEntry;
import com.agentx.ai.core.sandbox.WorkspaceSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

/**
 * Docker 沙箱后端。
 *
 * <p>实现 {@link SandboxBackend} SPI，通过 {@link DockerClient}（docker CLI）
 * 管理容器的完整生命周期：创建 → 物化 workspace → 快照 → 恢复 → 销毁。
 * Docker 专属配置（镜像、资源限制、容器工作目录、超时）在本类 Builder 中设置。
 *
 * <pre>{@code
 * DockerBackend backend = DockerBackend.builder()
 *         .image("ubuntu:22.04")
 *         .memoryMb(1024)
 *         .cpuCount(2)
 *         .networkDisabled(false)
 *         .build();
 * }</pre>
 *
 * @author bigchui
 */
public class DockerBackend implements SandboxBackend {

    private static final Logger log = LoggerFactory.getLogger(DockerBackend.class);

    public static final String DEFAULT_IMAGE = "ubuntu:22.04";
    public static final String DEFAULT_WORKSPACE_ROOT = "/workspace";
    public static final int DEFAULT_MEMORY_MB = 512;
    public static final int DEFAULT_CPU_COUNT = 1;
    public static final long DEFAULT_EXECUTION_TIMEOUT_MS = 120_000L;

    private final String image;
    private final String workspaceRoot;
    private final boolean networkDisabled;
    private final int memoryMb;
    private final int cpuCount;
    private final long executionTimeoutMs;

    private DockerBackend(Builder b) {
        this.image = b.image;
        this.workspaceRoot = b.workspaceRoot;
        this.networkDisabled = b.networkDisabled;
        this.memoryMb = b.memoryMb;
        this.cpuCount = b.cpuCount;
        this.executionTimeoutMs = b.executionTimeoutMs;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean isAvailable() {
        try {
            ProcessRunner.ProcessResult r = ProcessRunner.run(
                    List.of("docker", "ps"), 5_000L);
            return r.exitCode() == 0;
        } catch (Exception e) {
            log.debug("[DockerBackend] docker daemon 不可用: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public Sandbox createSandbox(WorkspaceSpec workspaceSpec, String containerName) throws Exception {
        DockerClient client = new DockerClient(executionTimeoutMs);

        client.createContainer(containerName, image, workspaceRoot,
                networkDisabled, memoryMb, cpuCount);
        client.startContainer(containerName);

        try {
            hydrateWorkspace(client, containerName, workspaceSpec);
        } catch (Exception e) {
            log.warn("[DockerBackend] workspace 物化失败，清理容器: {}", containerName, e);
            client.removeContainer(containerName);
            throw e;
        }

        return buildSandbox(client, containerName);
    }

    @Override
    public Sandbox findExisting(String containerName) {
        DockerClient client = new DockerClient(executionTimeoutMs);
        if (!client.isContainerRunning(containerName)) {
            return null;
        }
        log.info("[DockerBackend] 发现已运行容器，复用: {}", containerName);
        return buildSandbox(client, containerName);
    }

    @Override
    public InputStream exportWorkspace(Sandbox sandbox) throws Exception {
        DockerSandbox ds = cast(sandbox);
        DockerClient client = ds.getDockerClient();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int exitCode = client.tarFromContainer(
                ds.getContainerName(), ds.getWorkingDirectory(), buffer);
        if (exitCode != 0) {
            throw new IOException("workspace 导出失败, exit=" + exitCode);
        }
        log.info("[DockerBackend] workspace 已导出: {} ({} bytes)",
                ds.getContainerName(), buffer.size());
        return new ByteArrayInputStream(buffer.toByteArray());
    }

    @Override
    public Sandbox restore(WorkspaceSpec workspaceSpec, String containerName,
                           InputStream tarStream) throws Exception {
        DockerClient client = new DockerClient(executionTimeoutMs);

        client.createContainer(containerName, image, workspaceRoot,
                networkDisabled, memoryMb, cpuCount);
        client.startContainer(containerName);

        try {
            int exitCode = client.tarToContainer(containerName, workspaceRoot, tarStream);
            if (exitCode != 0) {
                throw new IOException("workspace 恢复失败, exit=" + exitCode);
            }
            hydrateWorkspace(client, containerName, workspaceSpec);
        } catch (Exception e) {
            log.warn("[DockerBackend] 恢复失败，清理容器: {}", containerName, e);
            client.removeContainer(containerName);
            throw e;
        }

        log.info("[DockerBackend] 从快照恢复完成: {}", containerName);
        return buildSandbox(client, containerName);
    }

    @Override
    public void destroy(Sandbox sandbox) {
        DockerSandbox ds = cast(sandbox);
        ds.getDockerClient().removeContainer(ds.getContainerName());
    }

    // ==================== 内部方法 ====================

    /**
     * 物化 workspace 规格（DirCopy / FileCopy / InlineText）。
     */
    private void hydrateWorkspace(DockerClient client, String containerName,
                                  WorkspaceSpec spec) {
        if (spec == null || spec.isEmpty()) {
            return;
        }
        for (WorkspaceEntry entry : spec.entries()) {
            switch (entry) {
                case WorkspaceEntry.DirCopy dc ->
                        client.copyToContainer(containerName, dc.hostSource(), dc.containerPath());
                case WorkspaceEntry.FileCopy fc ->
                        client.copyToContainer(containerName, fc.hostSource(), fc.containerPath());
                case WorkspaceEntry.InlineText it ->
                        client.writeInlineText(containerName, it.content(), it.containerPath());
            }
        }
    }

    private DockerSandbox buildSandbox(DockerClient client, String containerName) {
        DockerExecutionBackend backend = new DockerExecutionBackend(
                client, containerName, workspaceRoot, executionTimeoutMs);
        return new DockerSandbox(client, containerName, workspaceRoot, backend);
    }

    private DockerSandbox cast(Sandbox sandbox) {
        if (!(sandbox instanceof DockerSandbox ds)) {
            throw new IllegalArgumentException(
                    "期望 DockerSandbox，实际: " + sandbox.getClass().getName());
        }
        return ds;
    }

    public static class Builder {

        private String image = DEFAULT_IMAGE;
        private String workspaceRoot = DEFAULT_WORKSPACE_ROOT;
        private boolean networkDisabled = true;
        private int memoryMb = DEFAULT_MEMORY_MB;
        private int cpuCount = DEFAULT_CPU_COUNT;
        private long executionTimeoutMs = DEFAULT_EXECUTION_TIMEOUT_MS;

        /**
         * 容器镜像（要求镜像内含 sh / tar / base64 / ls 等基础命令）。
         */
        public Builder image(String image) {
            this.image = Objects.requireNonNull(image, "image must not be null");
            return this;
        }

        /**
         * 容器内 workspace 根目录。
         */
        public Builder workspaceRoot(String root) {
            this.workspaceRoot = Objects.requireNonNull(root, "workspaceRoot must not be null");
            return this;
        }

        /**
         * true 时容器以 {@code --network=none} 启动，禁用网络。
         */
        public Builder networkDisabled(boolean disabled) {
            this.networkDisabled = disabled;
            return this;
        }

        public Builder memoryMb(int mb) {
            this.memoryMb = mb;
            return this;
        }

        public Builder cpuCount(int count) {
            this.cpuCount = count;
            return this;
        }

        /**
         * 单条容器命令（exec / cp / tar）的超时毫秒数。
         */
        public Builder executionTimeoutMs(long ms) {
            this.executionTimeoutMs = ms;
            return this;
        }

        public DockerBackend build() {
            return new DockerBackend(this);
        }
    }
}
