package com.agentx.ai.core.sandbox.local;

import com.agentx.ai.core.sandbox.ExecutionBackend;
import com.agentx.ai.core.sandbox.Sandbox;
import com.agentx.ai.core.sandbox.SandboxBackend;
import com.agentx.ai.core.sandbox.WorkspaceSpec;
import com.agentx.ai.core.tools.ShellSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 本地执行后端 — 在宿主机上模拟沙箱行为，用于开发调试。
 *
 * <p>与 {@code DockerExecutionBackend} 实现相同接口，但命令和文件操作
 * 全部在宿主机文件系统上执行，不依赖 Docker。所有路径限定在
 * {@code workspaceDir} 内，提供基础隔离（注意：无操作系统级隔离，
 * 勿用于执行不受信任的代码）。
 *
 * <p>同一 backend 实例的所有会话共享同一个 {@code workspaceDir}，
 * 不做会话级隔离；需要隔离时为不同会话构造不同实例。
 * 文件常驻宿主机磁盘，无需快照（{@code supportsSnapshot() == false}）。
 *
 * <p>用法：
 * <pre>{@code
 * LocalExecutionBackend backend = new LocalExecutionBackend("/tmp/sandbox-test");
 * SandboxConfig config = SandboxConfig.builder()
 *         .backend(backend)
 *         .build();
 * }</pre>
 *
 * @author bigchui
 */
public class LocalExecutionBackend implements SandboxBackend, ExecutionBackend {

    private static final Logger log = LoggerFactory.getLogger(LocalExecutionBackend.class);

    private final Path workspaceDir;
    private final String workingDirectory;
    private final long defaultTimeoutMs;

    /**
     * @param workspaceDir 沙箱工作目录（绝对路径）
     */
    public LocalExecutionBackend(String workspaceDir) {
        this(workspaceDir, 120_000L);
    }

    /**
     * @param workspaceDir     沙箱工作目录（绝对路径）
     * @param defaultTimeoutMs 命令默认超时毫秒数
     */
    public LocalExecutionBackend(String workspaceDir, long defaultTimeoutMs) {
        this.workspaceDir = Path.of(workspaceDir).toAbsolutePath().normalize();
        this.workingDirectory = this.workspaceDir.toString().replace('\\', '/');
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    // ==================== ExecutionBackend ====================

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
        try {
            ensureWorkspace();
        } catch (IOException e) {
            return new ShellSessionManager.CommandResult(-1, "", "无法创建工作目录: " + e.getMessage(), workingDirectory);
        }
        long timeout = timeoutMs > 0 ? timeoutMs : defaultTimeoutMs;
        try {
            ProcessBuilder pb = createProcessBuilder(command);
            Process process = pb.start();

            String stdout = drain(process.getInputStream());
            String stderr = drain(process.getErrorStream());

            boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ShellSessionManager.CommandResult(-1, stdout, "命令超时 (" + timeout + "ms)", workingDirectory);
            }
            int exitCode = process.exitValue();
            return new ShellSessionManager.CommandResult(exitCode, stdout, stderr, workingDirectory);
        } catch (IOException e) {
            return new ShellSessionManager.CommandResult(-1, "", "IO Error: " + e.getMessage(), workingDirectory);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ShellSessionManager.CommandResult(-1, "", "Interrupted", workingDirectory);
        }
    }

    private ProcessBuilder createProcessBuilder(String command) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        ProcessBuilder pb;
        if (isWindows) {
            pb = new ProcessBuilder("cmd.exe", "/c", command);
        } else {
            pb = new ProcessBuilder("sh", "-c", command);
        }
        pb.directory(workspaceDir.toFile());
        pb.redirectErrorStream(false);
        return pb;
    }

    private static String drain(InputStream is) throws IOException {
        byte[] buf = new byte[8192];
        StringBuilder sb = new StringBuilder();
        int n;
        while ((n = is.read(buf)) != -1) {
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        if (sb.length() > 2_000_000) {
            sb.setLength(2_000_000);
            sb.append("\n... (truncated at 2MB)");
        }
        return sb.toString();
    }

    @Override
    public byte[] readFile(String path) throws IOException {
        Path resolved = resolvePath(path);
        return Files.readAllBytes(resolved);
    }

    @Override
    public void writeFile(String path, byte[] content) throws IOException {
        Path resolved = resolvePath(path);
        Path parent = resolved.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        Files.write(resolved, content);
    }

    @Override
    public List<String> listFiles(String dirPath) throws IOException {
        Path dir = resolvePath(dirPath);
        if (!Files.isDirectory(dir)) {
            throw new IOException("目录不存在: " + dirPath);
        }
        List<String> results = new ArrayList<>();
        try (Stream<Path> paths = Files.list(dir)) {
            paths.forEach(p -> {
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    results.add(p.getFileName() + "/");
                } else {
                    results.add(p.getFileName().toString());
                }
            });
        }
        Collections.sort(results);
        return results;
    }

    @Override
    public List<String> globFiles(String baseDir, String pattern) throws IOException {
        Path searchRoot = resolvePath(baseDir);
        if (!Files.exists(searchRoot)) {
            return List.of();
        }
        String normalizedPattern = pattern.replace('\\', '/');
        FileSystem fs = FileSystems.getDefault();
        PathMatcher matcher = fs.getPathMatcher("glob:" + normalizedPattern);

        List<String> matched = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(searchRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        Path relative = searchRoot.relativize(path);
                        return matcher.matches(relative) || matcher.matches(relative.getFileName());
                    })
                    .forEach(path -> matched.add(path.toString().replace('\\', '/')));
        }
        Collections.sort(matched);
        return matched;
    }

    @Override
    public String grep(String pattern, String path, String glob, String outputMode,
                       int beforeContext, int afterContext, boolean ignoreCase,
                       int headLimit, int offset) throws IOException {
        Path searchPath = resolveSearchPath(path);
        String mode = outputMode != null ? outputMode : "content";

        List<String> results = new ArrayList<>();
        Pattern regex = Pattern.compile(pattern, ignoreCase ? Pattern.CASE_INSENSITIVE : 0);

        List<Path> files = collectFiles(searchPath, glob);
        for (Path file : files) {
            List<String> lines = readLinesWithFallback(file);
            if (lines == null) continue;

            if ("files_with_matches".equals(mode)) {
                for (String line : lines) {
                    if (regex.matcher(line).find()) {
                        results.add(file.toString().replace('\\', '/'));
                        break;
                    }
                }
            } else if ("count".equals(mode)) {
                long count = lines.stream().filter(l -> regex.matcher(l).find()).count();
                if (count > 0) {
                    results.add(file.toString().replace('\\', '/') + ":" + count);
                }
            } else {
                for (int i = 0; i < lines.size(); i++) {
                    if (regex.matcher(lines.get(i)).find()) {
                        int start = Math.max(0, i - beforeContext);
                        int end = Math.min(lines.size(), i + afterContext + 1);
                        for (int j = start; j < end; j++) {
                            String prefix = (j == i) ? ":" : "-";
                            results.add(file.getFileName() + prefix + (j + 1) + ":" + lines.get(j));
                        }
                    }
                }
            }
        }

        if (results.isEmpty()) {
            return "No matches found.";
        }
        int startIdx = Math.min(offset, results.size());
        int endIdx = headLimit > 0 ? Math.min(startIdx + headLimit, results.size()) : results.size();
        return String.join("\n", results.subList(startIdx, endIdx));
    }

    private Path resolveSearchPath(String rawPath) {
        String p = (rawPath == null || rawPath.trim().isEmpty()) ? "." : rawPath;
        Path resolved = Path.of(p);
        return resolved.isAbsolute() ? resolved.normalize() : workspaceDir.resolve(resolved).normalize();
    }

    private List<Path> collectFiles(Path searchPath, String glob) throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.isRegularFile(searchPath)) {
            if (matchesGlob(searchPath.getFileName().toString(), glob)) {
                files.add(searchPath);
            }
        } else if (Files.isDirectory(searchPath)) {
            try (Stream<Path> paths = Files.walk(searchPath)) {
                paths.filter(Files::isRegularFile)
                        .filter(p -> matchesGlob(p.getFileName().toString(), glob))
                        .forEach(files::add);
            }
        }
        return files;
    }

    private static boolean matchesGlob(String fileName, String glob) {
        if (glob == null || glob.isEmpty() || glob.equals("*")) return true;
        String regex = glob.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        return fileName.matches(regex);
    }

    private static List<String> readLinesWithFallback(Path file) throws IOException {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            try {
                return Files.readAllLines(file, Charset.forName("GBK"));
            } catch (Exception e2) {
                return Files.readAllLines(file, StandardCharsets.ISO_8859_1);
            }
        }
    }

    // ==================== Path resolution ====================

    private Path resolvePath(String path) {
        String p = (path != null && !path.trim().isEmpty()) ? path : ".";
        Path resolved = workspaceDir.resolve(p).normalize();
        if (!resolved.startsWith(workspaceDir)) {
            throw new IllegalArgumentException("路径越界: " + path);
        }
        return resolved;
    }

    private void ensureWorkspace() throws IOException {
        if (!Files.exists(workspaceDir)) {
            Files.createDirectories(workspaceDir);
        }
    }

    // ==================== SandboxBackend ====================

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public boolean supportsSnapshot() {
        // 文件常驻宿主机磁盘，无需 tar 快照
        return false;
    }

    @Override
    public Sandbox createSandbox(WorkspaceSpec workspaceSpec, String containerName) throws Exception {
        ensureWorkspace();
        log.info("[LocalExecutionBackend] 创建本地沙箱: workspace={}", workspaceDir);
        return new LocalSandbox();
    }

    @Override
    public Sandbox findExisting(String containerName) {
        return null;
    }

    @Override
    public InputStream exportWorkspace(Sandbox sandbox) {
        // 本地模式文件已持久化在磁盘，无需 tar 快照
        return null;
    }

    @Override
    public Sandbox restore(WorkspaceSpec workspaceSpec, String containerName,
                           InputStream tarStream) throws Exception {
        return createSandbox(workspaceSpec, containerName);
    }

    @Override
    public void destroy(Sandbox sandbox) {
        log.debug("[LocalExecutionBackend] 销毁本地沙箱（保留文件）: {}", workspaceDir);
    }

    // ==================== LocalSandbox ====================

    private class LocalSandbox implements Sandbox {
        private final String containerName = "local-" + Integer.toHexString(hashCode());

        @Override
        public String getContainerName() {
            return containerName;
        }

        @Override
        public String getWorkingDirectory() {
            return workingDirectory;
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public ExecutionBackend getExecutionBackend() {
            return LocalExecutionBackend.this;
        }
    }
}
