package com.agentx.ai.core.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Shell Session Manager - 管理 Shell 会话
 *
 * 提供持久的 Shell 会话管理功能，支持：
 * - 会话持久化 - 多个命令共享同一个会话
 * - 命令执行 - 使用 ProcessBuilder 执行命令
 * - 输出截断保护 - 行数和字节数限制
 * - 超时控制 - 防止命令无限期运行
 * - 工作目录管理 - 持久化工作目录
 *
 * 使用示例：
 * {@code
 * ShellSessionManager manager = ShellSessionManager.builder()
 *     .maxLines(10000)
 *     .maxBytes(100000)
 *     .timeoutMs(120000)
 *     .build();
 *
 * CommandResult result = manager.executeCommand("sessionId", "ls -la", "/home/user");
 * }
 *
 * @author bigchui
 * 
 */
public class ShellSessionManager {

    private static final Logger log = LoggerFactory.getLogger(ShellSessionManager.class);

    private final int maxLines;
    private final int maxBytes;
    private final long timeoutMs;
    private final Charset charset;
    private final boolean mergeOutput;
    private final Map<String, ShellSession> sessions;

    private ShellSessionManager(Builder builder) {
        this.maxLines = builder.maxLines;
        this.maxBytes = builder.maxBytes;
        this.timeoutMs = builder.timeoutMs;
        this.charset = builder.charset;
        this.mergeOutput = builder.mergeOutput;
        this.sessions = new ConcurrentHashMap<>();
    }

    /**
     * 创建 Builder
     *
     * @return Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 执行命令
     *
     * @param sessionId 会话 ID
     * @param command   命令
     * @param directory 工作目录
     * @return 命令执行结果
     */
    public CommandResult executeCommand(String sessionId, String command, String directory) {
        ShellSession session = sessions.computeIfAbsent(sessionId, id -> {
            log.debug("Creating new shell session: {}", id);
            return new ShellSession(id);
        });

        // 更新工作目录
        if (directory != null && !directory.isEmpty()) {
            session.lastDirectory = directory;
        }

        // Windows 环境命令警告检测
        warnIfWindowsPatternIssue(command);

        // 执行命令
        return executeInSession(session, command);
    }

    /**
     * 检测并警告 Windows 环境下常见的命令问题
     *
     * @param command 命令
     */
    private void warnIfWindowsPatternIssue(String command) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (!isWindows) {
            return;
        }

        String trimmedCommand = command.trim();

        // 检测 mkdir -p 问题
        if (trimmedCommand.contains("mkdir") && trimmedCommand.contains("-p")) {
            log.warn("⚠️ WARNING: 'mkdir -p' detected on Windows! This will create a directory named '-p'.");
            log.warn("   Use 'mkdir' without -p instead (Windows automatically creates parent directories)");
            log.warn("   Command: {}", command);
        }

        // 检测 touch 命令问题
        if (trimmedCommand.startsWith("touch ")) {
            log.warn("⚠️ WARNING: 'touch' command detected on Windows! This command doesn't exist.");
            log.warn("   Use 'type nul > filename' or 'copy nul filename' instead");
            log.warn("   Command: {}", command);
        }
    }

    /**
     * 在会话中执行命令
     *
     * @param session 会话
     * @param command 命令
     * @return 命令执行结果
     */
    private CommandResult executeInSession(ShellSession session, String command) {
        StringBuilder output = new StringBuilder();
        StringBuilder errorOutput = new StringBuilder();
        int exitCode = -1;

        try {
            // 准备工作目录
            File workingDir = determineWorkingDirectory(session, command);

            // 构建进程
            ProcessBuilder processBuilder = new ProcessBuilder();

            // 根据操作系统选择 shell
            String[] shellCommand = buildShellCommand(command, session.lastDirectory);
            processBuilder.command(shellCommand);

            if (workingDir != null) {
                processBuilder.directory(workingDir);
            }

            // 合并标准错误和标准输出（可选）
            if (mergeOutput) {
                processBuilder.redirectErrorStream(true);
            }

            log.debug("Executing command in session {}: {} in directory {}",
                session.id, command, workingDir);

            // 启动进程
            Process process = processBuilder.start();

            // 读取输出
            if (mergeOutput) {
                // 合并模式：只读取 stdout
                exitCode = readOutput(process.getInputStream(), output, null);
            } else {
                // 分离模式：读取 stdout 和 stderr
                Thread outputThread = new Thread(() ->
                    readStream(process.getInputStream(), output));
                Thread errorThread = new Thread(() ->
                    readStream(process.getErrorStream(), errorOutput));

                outputThread.start();
                errorThread.start();

                // 等待进程完成
                boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);

                if (!finished) {
                    log.warn("Command timed out after {}ms in session {}", timeoutMs, session.id);
                    process.destroyForcibly();
                    exitCode = -1;
                } else {
                    exitCode = process.exitValue();
                }

                // 等待输出线程完成
                outputThread.join(1000);
                errorThread.join(1000);
            }

            // 更新会话的最后工作目录
            updateLastDirectory(session, command, workingDir, exitCode);

            String stdOut = truncateOutput(output.toString());
            String stdErr = truncateOutput(errorOutput.toString());

            log.debug("Command completed in session {} with exit code {}", session.id, exitCode);

            return new CommandResult(exitCode, stdOut, stdErr, workingDir != null ? workingDir.getPath() : null);

        } catch (IOException e) {
            log.error("IO error executing command in session {}: {}", session.id, e.getMessage());
            return new CommandResult(-1, "", "IO Error: " + e.getMessage(), session.lastDirectory);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Command execution interrupted in session {}", session.id);
            return new CommandResult(-1, "", "Interrupted", session.lastDirectory);
        }
    }

    /**
     * 确定工作目录
     */
    private File determineWorkingDirectory(ShellSession session, String command) {
        String targetDir = session.lastDirectory;

        // 如果命令包含 cd，尝试从命令中提取目录
        if (command.trim().startsWith("cd ")) {
            String dir = command.trim().substring(3).trim();
            if (!dir.isEmpty()) {
                File dirFile = new File(dir);
                if (dirFile.isAbsolute()) {
                    targetDir = dir;
                } else if (session.lastDirectory != null) {
                    targetDir = new File(session.lastDirectory, dir).getAbsolutePath();
                } else {
                    targetDir = new File(dir).getAbsolutePath();
                }
            }
        }

        if (targetDir != null) {
            File dir = new File(targetDir);
            if (dir.exists() && dir.isDirectory()) {
                return dir;
            }
        }

        // 默认使用当前目录
        return new File(System.getProperty("user.dir"));
    }

    /**
     * 构建命令数组
     */
    private String[] buildShellCommand(String command, String workingDirectory) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        if (isWindows) {
            // Windows: 使用 cmd.exe
            if (workingDirectory != null && !workingDirectory.isEmpty()) {
                return new String[]{"cmd.exe", "/c", "cd /d \"" + workingDirectory + "\" && " + command};
            }
            return new String[]{"cmd.exe", "/c", command};
        } else {
            // Unix/Linux/Mac: 使用 bash
            if (workingDirectory != null && !workingDirectory.isEmpty()) {
                return new String[]{"bash", "-c", "cd \"" + workingDirectory + "\" && " + command};
            }
            return new String[]{"bash", "-c", command};
        }
    }

    /**
     * 读取输出流
     */
    private int readStream(java.io.InputStream stream, StringBuilder output) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, charset))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        } catch (IOException e) {
            log.warn("Error reading stream: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * 读取输出（合并模式）
     */
    private int readOutput(java.io.InputStream stream, StringBuilder output, StringBuilder error) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, charset))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            return 0;
        } catch (IOException e) {
            log.warn("Error reading output: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * 更新最后工作目录
     */
    private void updateLastDirectory(ShellSession session, String command, File workingDir, int exitCode) {
        if (command.trim().startsWith("cd ") && exitCode == 0) {
            String dir = command.trim().substring(3).trim();
            if (!dir.isEmpty()) {
                File newDir = new File(dir);
                if (!newDir.isAbsolute() && workingDir != null) {
                    newDir = new File(workingDir, dir);
                }
                if (newDir.exists() && newDir.isDirectory()) {
                    session.lastDirectory = newDir.getAbsolutePath();
                    log.debug("Updated working directory for session {}: {}", session.id, session.lastDirectory);
                }
            }
        }
    }

    /**
     * 截断输出
     */
    private String truncateOutput(String output) {
        if (output == null || output.isEmpty()) {
            return "";
        }

        // 检查行数限制
        String[] lines = output.split("\n");
        if (lines.length > maxLines) {
            String truncated = String.join("\n",
                java.util.Arrays.copyOfRange(lines, 0, maxLines));
            return truncated + "\n... (output truncated, too many lines)";
        }

        // 检查字节数限制
        byte[] bytes = output.getBytes(charset);
        if (bytes.length > maxBytes) {
            String truncated = new String(bytes, 0, maxBytes, charset);
            return truncated + "\n... (output truncated, too large)";
        }

        return output;
    }

    /**
     * 移除会话
     *
     * @param sessionId 会话 ID
     */
    public void removeSession(String sessionId) {
        ShellSession session = sessions.remove(sessionId);
        if (session != null) {
            log.debug("Removed shell session: {}", sessionId);
        }
    }

    /**
     * 获取会话
     *
     * @param sessionId 会话 ID
     * @return 会话，如果不存在返回 null
     */
    public ShellSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * 获取所有会话
     *
     * @return 会话映射
     */
    public Map<String, ShellSession> getSessions() {
        return new ConcurrentHashMap<>(sessions);
    }

    /**
     * 命令执行结果
     */
    public static class CommandResult {
        private final int exitCode;
        private final String output;
        private final String error;
        private final String workingDirectory;

        public CommandResult(int exitCode, String output, String error, String workingDirectory) {
            this.exitCode = exitCode;
            this.output = output;
            this.error = error;
            this.workingDirectory = workingDirectory;
        }

        public int exitCode() {
            return exitCode;
        }

        public String output() {
            return output;
        }

        public String error() {
            return error;
        }

        public String workingDirectory() {
            return workingDirectory;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }

        @Override
        public String toString() {
            return "CommandResult{" +
                "exitCode=" + exitCode +
                ", output='" + output + '\'' +
                ", error='" + error + '\'' +
                ", workingDirectory='" + workingDirectory + '\'' +
                '}';
        }
    }

    /**
     * Shell 会话
     */
    public static class ShellSession {
        private final String id;
        private volatile String lastDirectory;
        private final long createdAt;

        public ShellSession(String id) {
            this.id = id;
            this.createdAt = System.currentTimeMillis();
            this.lastDirectory = System.getProperty("user.dir");
        }

        public String id() {
            return id;
        }

        public String lastDirectory() {
            return lastDirectory;
        }

        public long createdAt() {
            return createdAt;
        }

        public void setLastDirectory(String directory) {
            this.lastDirectory = directory;
        }

        @Override
        public String toString() {
            return "ShellSession{" +
                "id='" + id + '\'' +
                ", lastDirectory='" + lastDirectory + '\'' +
                ", createdAt=" + createdAt +
                '}';
        }
    }

    /**
     * Builder 模式
     */
    public static class Builder {
        private int maxLines = 10000;
        private int maxBytes = 100000;
        private long timeoutMs = 120000;
        private Charset charset = StandardCharsets.UTF_8;
        private boolean mergeOutput = false;

        /**
         * 设置最大行数限制
         *
         * @param maxLines 最大行数
         * @return this
         */
        public Builder maxLines(int maxLines) {
            this.maxLines = maxLines;
            return this;
        }

        /**
         * 设置最大字节数限制
         *
         * @param maxBytes 最大字节数
         * @return this
         */
        public Builder maxBytes(int maxBytes) {
            this.maxBytes = maxBytes;
            return this;
        }

        /**
         * 设置超时时间（毫秒）
         *
         * @param timeoutMs 超时时间
         * @return this
         */
        public Builder timeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        /**
         * 设置字符编码
         *
         * @param charset 字符编码
         * @return this
         */
        public Builder charset(Charset charset) {
            this.charset = charset;
            return this;
        }

        /**
         * 设置是否合并标准输出和错误输出
         *
         * @param mergeOutput 是否合并
         * @return this
         */
        public Builder mergeOutput(boolean mergeOutput) {
            this.mergeOutput = mergeOutput;
            return this;
        }

        /**
         * 构建 ShellSessionManager
         *
         * @return ShellSessionManager 实例
         */
        public ShellSessionManager build() {
            return new ShellSessionManager(this);
        }
    }
}
