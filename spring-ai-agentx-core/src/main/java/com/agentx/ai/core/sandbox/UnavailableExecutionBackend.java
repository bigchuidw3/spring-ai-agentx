package com.agentx.ai.core.sandbox;

import com.agentx.ai.core.tools.ShellSessionManager;

import java.io.IOException;
import java.util.List;

/**
 * 严格模式下沙箱不可用的占位执行后端。
 *
 * <p>沙箱获取失败时注入到 ToolContext，使所有工具调用直接返回错误，
 * 保证命令绝不降级到宿主机执行（fail-closed）。
 *
 * @author bigchui
 */
public final class UnavailableExecutionBackend implements ExecutionBackend {

    public static final UnavailableExecutionBackend INSTANCE = new UnavailableExecutionBackend();

    private static final String REASON = "沙箱不可用（严格模式），命令未执行";

    private UnavailableExecutionBackend() {
    }

    @Override
    public boolean isSandboxed() {
        return true;
    }

    @Override
    public String getWorkingDirectory() {
        return "/sandbox-unavailable";
    }

    @Override
    public ShellSessionManager.CommandResult executeCommand(String command, long timeoutMs) {
        return new ShellSessionManager.CommandResult(-1, "", REASON, getWorkingDirectory());
    }

    @Override
    public byte[] readFile(String path) throws IOException {
        throw new IOException(REASON);
    }

    @Override
    public void writeFile(String path, byte[] content) throws IOException {
        throw new IOException(REASON);
    }

    @Override
    public List<String> listFiles(String dirPath) throws IOException {
        throw new IOException(REASON);
    }

    @Override
    public List<String> globFiles(String baseDir, String pattern) throws IOException {
        throw new IOException(REASON);
    }

    @Override
    public String grep(String pattern, String path, String glob, String outputMode,
                       int beforeContext, int afterContext, boolean ignoreCase,
                       int headLimit, int offset) throws IOException {
        throw new IOException(REASON);
    }
}
