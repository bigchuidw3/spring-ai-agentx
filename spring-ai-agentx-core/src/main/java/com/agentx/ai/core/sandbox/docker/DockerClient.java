package com.agentx.ai.core.sandbox.docker;

import com.agentx.ai.core.exception.AgentErrorCode;
import com.agentx.ai.core.sandbox.SandboxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Docker CLI 封装。
 *
 * <p>所有操作通过 {@link ProcessRunner} 调用 {@code docker} CLI，零 SDK 依赖。
 * 只负责执行命令和返回结果，不做业务判断。
 *
 * @author bigchui
 */
public class DockerClient {

    private static final Logger log = LoggerFactory.getLogger(DockerClient.class);

    private final long defaultTimeoutMs;

    public DockerClient(long defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    // ==================== 容器生命周期 ====================

    /**
     * 创建容器（不启动）。
     */
    public void createContainer(String name, String image, String workspaceRoot,
                                boolean networkDisabled, int memoryMb, int cpuCount) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("create");
        cmd.add("--name");
        cmd.add(name);
        if (networkDisabled) {
            cmd.add("--network=none");
        }
        cmd.add("--memory=" + memoryMb + "m");
        cmd.add("--cpus=" + cpuCount);
        cmd.add("-w");
        cmd.add(workspaceRoot);
        cmd.add(image);
        cmd.add("tail");
        cmd.add("-f");
        cmd.add("/dev/null");

        ProcessRunner.ProcessResult r = ProcessRunner.run(cmd, defaultTimeoutMs);
        if (r.exitCode() != 0) {
            throw new SandboxException(AgentErrorCode.SANDBOX_CONTAINER_NOT_FOUND,
                    "创建容器失败 [" + name + "]: " + r.stderr());
        }
        log.info("[DockerClient] 容器已创建: {}", name);
    }

    /**
     * 启动容器。
     */
    public void startContainer(String name) {
        ProcessRunner.ProcessResult r = ProcessRunner.run(
                List.of("docker", "start", name), defaultTimeoutMs);
        if (r.exitCode() != 0) {
            throw new SandboxException(AgentErrorCode.SANDBOX_CONTAINER_NOT_FOUND,
                    "启动容器失败 [" + name + "]: " + r.stderr());
        }
        log.info("[DockerClient] 容器已启动: {}", name);
    }

    /**
     * 强制删除容器（docker rm -f）。
     */
    public void removeContainer(String name) {
        ProcessRunner.ProcessResult r = ProcessRunner.run(
                List.of("docker", "rm", "-f", name), defaultTimeoutMs);
        if (r.exitCode() != 0) {
            log.warn("[DockerClient] 删除容器失败 [{}]: {}", name, r.stderr());
        } else {
            log.info("[DockerClient] 容器已删除: {}", name);
        }
    }

    /**
     * 检查容器是否正在运行。
     */
    public boolean isContainerRunning(String name) {
        ProcessRunner.ProcessResult r = ProcessRunner.run(
                List.of("docker", "inspect", "--format={{.State.Running}}", name),
                defaultTimeoutMs);
        return r.exitCode() == 0 && r.stdout().trim().equals("true");
    }

    // ==================== 命令执行 ====================

    /**
     * 在容器内执行命令（通过 sh -c）。
     */
    public ProcessRunner.ProcessResult exec(String name, String workDir,
                                            String command, long timeoutMs) {
        List<String> cmd = List.of("docker", "exec", "-w", workDir, name, "sh", "-c", command);
        return ProcessRunner.run(cmd, timeoutMs);
    }

    /**
     * 在容器内执行命令，stdin / stdout 以流式连接。
     */
    public int execWithPipes(String name, String workDir, String command,
                             InputStream stdin, OutputStream stdout, long timeoutMs) {
        List<String> cmd = List.of("docker", "exec", "-i", "-w", workDir, name, "sh", "-c", command);
        return ProcessRunner.runWithStream(cmd, stdin, stdout, timeoutMs);
    }

    // ==================== 文件 I/O ====================

    /**
     * 读取容器内文件为原始字节。
     *
     * <p>通过 {@code docker exec {name} base64 {path}} 读取 base64 文本，Java 端解码。
     * path 作为 base64 的参数传递，不走 shell，无注入风险。
     */
    public byte[] readFile(String name, String path) {
        ProcessRunner.ProcessResult r = ProcessRunner.run(
                List.of("docker", "exec", name, "base64", path), defaultTimeoutMs);
        if (r.exitCode() != 0) {
            throw new SandboxException(AgentErrorCode.SANDBOX_CONTAINER_NOT_FOUND,
                    "读取文件失败 [" + name + ":" + path + "]: " + r.stderr());
        }
        String b64 = r.stdout().replaceAll("\\s", "");
        return Base64.getDecoder().decode(b64);
    }

    /**
     * 写入容器内文件。
     *
     * <p>通过 {@code docker exec -i {name} tee {path}} 管道写入原始字节。
     * path 作为 tee 的参数传递，不走 shell，无注入风险。
     */
    public void writeFile(String name, String path, byte[] content) {
        int exitCode = ProcessRunner.runWithStream(
                List.of("docker", "exec", "-i", name, "tee", path),
                new ByteArrayInputStream(content), null, defaultTimeoutMs);
        if (exitCode != 0) {
            throw new SandboxException(AgentErrorCode.SANDBOX_CONTAINER_NOT_FOUND,
                    "写入文件失败 [" + name + ":" + path + "]");
        }
    }

    /**
     * 列出目录内容（含类型标识，如 file.txt、subdir/）。
     */
    public String listFiles(String name, String dirPath) {
        ProcessRunner.ProcessResult r = ProcessRunner.run(
                List.of("docker", "exec", name, "ls", "-1F", dirPath), defaultTimeoutMs);
        if (r.exitCode() != 0) {
            throw new SandboxException(AgentErrorCode.SANDBOX_CONTAINER_NOT_FOUND,
                    "列目录失败 [" + name + ":" + dirPath + "]: " + r.stderr());
        }
        return r.stdout();
    }

    /**
     * 按 glob 模式搜索文件。
     *
     * <p>find 已递归搜索，因此先剥离所有 {@code ** /} 前缀；
     * 剩余部分含 {@code /} 时用 {@code -path}，否则用 {@code -name} 匹配文件名。
     */
    public String globFiles(String name, String baseDir, String pattern) {
        String clean = pattern.replaceAll("\\*\\*/", "");
        if (clean.isEmpty()) {
            clean = "*";
        }
        String flag = clean.contains("/") ? "-path" : "-name";
        ProcessRunner.ProcessResult r = ProcessRunner.run(
                List.of("docker", "exec", name, "find", baseDir, flag, clean),
                defaultTimeoutMs);
        if (r.exitCode() != 0) {
            throw new SandboxException(AgentErrorCode.SANDBOX_CONTAINER_NOT_FOUND,
                    "glob 搜索失败 [" + name + ":" + baseDir + "]: " + r.stderr());
        }
        return r.stdout();
    }

    /**
     * 在容器内执行 grep（优先用 ripgrep，不可用时 fallback 到 grep）。
     */
    public ProcessRunner.ProcessResult grep(String name, List<String> grepArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("exec");
        cmd.add(name);
        cmd.add("rg");
        cmd.addAll(grepArgs);
        return ProcessRunner.run(cmd, defaultTimeoutMs);
    }

    /**
     * 在容器内执行 grep 的 fallback（使用 GNU grep）。
     */
    public ProcessRunner.ProcessResult grepFallback(String name, List<String> grepArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("exec");
        cmd.add(name);
        cmd.add("grep");
        cmd.addAll(grepArgs);
        return ProcessRunner.run(cmd, defaultTimeoutMs);
    }

    // ==================== Tar 流（快照 / 恢复） ====================

    /**
     * 将容器内目录导出为 tar 流，写入 OutputStream。
     */
    public int tarFromContainer(String name, String containerDir, OutputStream target) {
        return ProcessRunner.runWithStream(
                List.of("docker", "exec", name, "tar", "-cf", "-", "-C", containerDir, "."),
                null, target, defaultTimeoutMs);
    }

    /**
     * 将 InputStream 中的 tar 流导入容器目录。
     */
    public int tarToContainer(String name, String containerDir, InputStream source) {
        return ProcessRunner.runWithStream(
                List.of("docker", "exec", "-i", name, "tar", "-xf", "-", "-C", containerDir),
                source, null, defaultTimeoutMs);
    }

    // ==================== 文件拷贝（workspace 物化） ====================

    /**
     * 将宿主机目录或文件拷贝到容器。
     */
    public void copyToContainer(String name, Path hostSource, String containerPath) {
        ProcessRunner.ProcessResult r = ProcessRunner.run(
                List.of("docker", "cp", hostSource.toString(), name + ":" + containerPath),
                defaultTimeoutMs);
        if (r.exitCode() != 0) {
            throw new SandboxException(AgentErrorCode.SANDBOX_CONTAINER_NOT_FOUND,
                    "拷贝到容器失败 [" + name + ":" + containerPath + "]: " + r.stderr());
        }
    }

    // ==================== 镜像 ====================

    /**
     * 检查本地是否已有镜像。
     */
    public boolean imageExists(String image) {
        ProcessRunner.ProcessResult r = ProcessRunner.run(
                List.of("docker", "image", "inspect", image), defaultTimeoutMs);
        return r.exitCode() == 0;
    }

    /**
     * 拉取镜像。
     */
    public void pullImage(String image) {
        log.info("[DockerClient] 拉取镜像: {}", image);
        ProcessRunner.ProcessResult r = ProcessRunner.run(
                List.of("docker", "pull", image), 300_000L);
        if (r.exitCode() != 0) {
            throw new SandboxException.ImagePullFailed(image, r.stderr(), null);
        }
    }

    // ==================== 文件写入（workspace 物化） ====================

    /**
     * 将内联文本写入容器目标路径（先写到宿主机临时文件，再 docker cp）。
     */
    public void writeInlineText(String name, String content, String containerPath) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("agentx-sandbox-inline-", ".txt");
            Files.write(tempFile, content.getBytes(StandardCharsets.UTF_8));
            copyToContainer(name, tempFile, containerPath);
        } catch (IOException e) {
            throw new SandboxException(AgentErrorCode.SANDBOX_CONTAINER_NOT_FOUND,
                    "写入内联文本失败 [" + name + ":" + containerPath + "]: " + e.getMessage(), e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // 静默删除
                }
            }
        }
    }
}
