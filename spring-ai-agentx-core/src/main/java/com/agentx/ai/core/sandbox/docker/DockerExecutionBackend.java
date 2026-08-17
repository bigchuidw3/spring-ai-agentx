package com.agentx.ai.core.sandbox.docker;

import com.agentx.ai.core.exception.AgentErrorCode;
import com.agentx.ai.core.sandbox.ExecutionBackend;
import com.agentx.ai.core.sandbox.SandboxException;
import com.agentx.ai.core.tools.ShellSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Docker 容器执行后端。
 *
 * <p>实现 {@link ExecutionBackend}，把所有工具 I/O 调用委托给 {@link DockerClient}
 * 在容器内执行。每个运行中的 {@link DockerSandbox} 持有一个本类实例。
 *
 * @author bigchui
 */
public class DockerExecutionBackend implements ExecutionBackend {

    private static final Logger log = LoggerFactory.getLogger(DockerExecutionBackend.class);

    private final DockerClient client;
    private final String containerName;
    private final String workingDirectory;
    private final long defaultTimeoutMs;

    public DockerExecutionBackend(DockerClient client, String containerName,
                                  String workingDirectory, long defaultTimeoutMs) {
        this.client = client;
        this.containerName = containerName;
        this.workingDirectory = workingDirectory;
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    @Override
    public boolean isSandboxed() {
        return true;
    }

    @Override
    public String getWorkingDirectory() {
        return workingDirectory;
    }

    @Override
    public ShellSessionManager.CommandResult executeCommand(String command, long timeoutMs) {
        long timeout = timeoutMs > 0 ? timeoutMs : defaultTimeoutMs;
        ProcessRunner.ProcessResult r = client.exec(containerName, workingDirectory, command, timeout);
        return new ShellSessionManager.CommandResult(
                r.exitCode(), r.stdout(), r.stderr(), workingDirectory);
    }

    @Override
    public byte[] readFile(String path) throws IOException {
        try {
            return client.readFile(containerName, path);
        } catch (SandboxException e) {
            throw new IOException("读取文件失败: " + path, e);
        }
    }

    @Override
    public void writeFile(String path, byte[] content) throws IOException {
        try {
            client.writeFile(containerName, path, content);
        } catch (SandboxException e) {
            throw new IOException("写入文件失败: " + path, e);
        }
    }

    @Override
    public List<String> listFiles(String dirPath) throws IOException {
        try {
            String output = client.listFiles(containerName, dirPath);
            return output.lines()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        } catch (SandboxException e) {
            throw new IOException("列目录失败: " + dirPath, e);
        }
    }

    @Override
    public List<String> globFiles(String baseDir, String pattern) throws IOException {
        try {
            String output = client.globFiles(containerName, baseDir, pattern);
            return output.lines()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        } catch (SandboxException e) {
            throw new IOException("glob 搜索失败: " + baseDir, e);
        }
    }

    @Override
    public String grep(String pattern, String path, String glob, String outputMode,
                       int beforeContext, int afterContext, boolean ignoreCase,
                       int headLimit, int offset) throws IOException {
        List<String> rgArgs = buildRipgrepArgs(pattern, path, glob, outputMode,
                beforeContext, afterContext, ignoreCase);

        ProcessRunner.ProcessResult r = client.grep(containerName, rgArgs);

        // rg 不存在 → fallback 到 GNU grep
        if (isCommandNotFound(r)) {
            log.debug("[DockerExecutionBackend] rg 不可用，fallback 到 grep");
            List<String> grepArgs = buildGrepArgs(pattern, path, glob, outputMode,
                    beforeContext, afterContext, ignoreCase);
            r = client.grepFallback(containerName, grepArgs);
        }

        // exit code: 0=有匹配, 1=无匹配, 2=错误
        if (r.exitCode() >= 2) {
            throw new IOException("grep 错误: " + r.stderr());
        }

        return sliceOutput(r.stdout(), headLimit, offset);
    }

    /**
     * 构建 ripgrep 参数列表。
     */
    private List<String> buildRipgrepArgs(String pattern, String path, String glob,
                                          String outputMode, int beforeContext,
                                          int afterContext, boolean ignoreCase) {
        List<String> args = new ArrayList<>();
        args.add("--color");
        args.add("never");
        if (ignoreCase) {
            args.add("-i");
        }
        if (beforeContext > 0) {
            args.add("-B");
            args.add(String.valueOf(beforeContext));
        }
        if (afterContext > 0) {
            args.add("-A");
            args.add(String.valueOf(afterContext));
        }
        if (glob != null && !glob.isBlank()) {
            args.add("-g");
            args.add(glob);
        }
        if ("files_with_matches".equals(outputMode)) {
            args.add("-l");
        } else if ("count".equals(outputMode)) {
            args.add("-c");
        }
        args.add(pattern);
        args.add(path);
        return args;
    }

    /**
     * 构建 GNU grep 参数列表。
     */
    private List<String> buildGrepArgs(String pattern, String path, String glob,
                                       String outputMode, int beforeContext,
                                       int afterContext, boolean ignoreCase) {
        List<String> args = new ArrayList<>();
        args.add("-r");
        args.add("-n");
        if (ignoreCase) {
            args.add("-i");
        }
        if (beforeContext > 0) {
            args.add("-B");
            args.add(String.valueOf(beforeContext));
        }
        if (afterContext > 0) {
            args.add("-A");
            args.add(String.valueOf(afterContext));
        }
        if ("files_with_matches".equals(outputMode)) {
            args.add("-l");
        } else if ("count".equals(outputMode)) {
            args.add("-c");
        }
        if (glob != null && !glob.isBlank()) {
            args.add("--include=" + glob);
        }
        args.add(pattern);
        args.add(path);
        return args;
    }

    /**
     * 判断是否为「命令不存在」。
     */
    private boolean isCommandNotFound(ProcessRunner.ProcessResult r) {
        return r.exitCode() == 127
                || (r.exitCode() != 0 && r.stderr().contains("not found")
                && r.stderr().contains("rg"));
    }

    /**
     * 在 Java 端切片输出（offset / headLimit）。
     */
    private String sliceOutput(String output, int headLimit, int offset) {
        if (offset <= 0 && headLimit <= 0) {
            return output;
        }
        List<String> lines = output.lines().toList();
        int start = Math.min(offset, lines.size());
        int end = headLimit > 0 ? Math.min(start + headLimit, lines.size()) : lines.size();
        return String.join("\n", lines.subList(start, end));
    }
}
