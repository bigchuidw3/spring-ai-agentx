package com.agentx.ai.core.sandbox;

import com.agentx.ai.core.tools.ShellSessionManager;

import java.io.IOException;
import java.util.List;

/**
 * 工具执行后端接口。
 *
 * <p>抽象「命令在哪里执行、文件在哪里读写」，让 {@code BashTool}、
 * {@code FileSystemTools}、{@code GrepTool} 等工具方法统一判断：
 * <ul>
 *   <li>{@link #isSandboxed()} 返回 {@code true} → 委托本后端（容器内执行）</li>
 *   <li>{@code null} 或返回 {@code false} → 工具原有宿主机逻辑</li>
 * </ul>
 *
 * <p>实现方：
 * <ul>
 *   <li>{@code DockerExecutionBackend} — 通过 {@code docker exec} 在容器内执行</li>
 *   <li>未来可扩展 {@code K8sExecutionBackend}、{@code RemoteExecutionBackend} 等</li>
 * </ul>
 *
 * @author bigchui
 */
public interface ExecutionBackend {

    /**
     * 是否在沙箱中执行。
     *
     * @return 沙箱实现返回 {@code true}；宿主模式返回 {@code false}
     */
    boolean isSandboxed();

    /**
     * 获取工作目录（容器内绝对路径，如 {@code /workspace}）。
     *
     * @return 工作目录路径
     */
    String getWorkingDirectory();

    /**
     * 执行 Shell 命令（无状态，不保持 cd / env）。
     *
     * @param command   要执行的命令
     * @param timeoutMs 超时毫秒数
     * @return 命令执行结果
     */
    ShellSessionManager.CommandResult executeCommand(String command, long timeoutMs);

    /**
     * 读取文件原始字节。
     *
     * @param path 文件路径（容器内绝对路径或相对工作目录的路径）
     * @return 文件内容字节
     * @throws IOException 文件不存在或读取失败
     */
    byte[] readFile(String path) throws IOException;

    /**
     * 写入文件（覆盖写）。
     *
     * @param path    文件路径
     * @param content 文件内容字节
     * @throws IOException 写入失败
     */
    void writeFile(String path, byte[] content) throws IOException;

    /**
     * 列出目录下的文件和子目录。
     *
     * @param dirPath 目录路径
     * @return 条目名称列表（含类型标识，如 {@code file.txt}、{@code subdir/}）
     * @throws IOException 目录不存在或读取失败
     */
    List<String> listFiles(String dirPath) throws IOException;

    /**
     * 按 glob 模式匹配文件。
     *
     * @param baseDir 基目录
     * @param pattern glob 模式（如 &#42;&#42;&#47;*.java）
     * @return 匹配到的文件路径列表
     * @throws IOException 搜索失败
     */
    List<String> globFiles(String baseDir, String pattern) throws IOException;

    /**
     * 按正则模式搜索文件内容。
     *
     * @param pattern       正则模式
     * @param path          搜索路径
     * @param glob          文件过滤 glob（可为 {@code null}）
     * @param outputMode    输出模式：{@code content} / {@code files_with_matches} / {@code count}
     * @param beforeContext 上下文行数（前）
     * @param afterContext  上下文行数（后）
     * @param ignoreCase    是否忽略大小写
     * @param headLimit     最多输出行数（{@code <= 0} 不限制）
     * @param offset        跳过前 N 条匹配
     * @return 搜索结果文本
     * @throws IOException 搜索失败
     */
    String grep(String pattern, String path, String glob, String outputMode,
                int beforeContext, int afterContext, boolean ignoreCase,
                int headLimit, int offset) throws IOException;
}
