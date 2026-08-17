package com.agentx.ai.core.sandbox;

/**
 * 运行时沙箱实例。
 *
 * <p>表示一个已创建并运行的沙箱容器，持有其标识与工作目录，
 * 并提供工具执行入口 {@link #getExecutionBackend()}。
 *
 * <p>生命周期由 {@link SandboxBackend} 管理（创建 / 快照 / 恢复 / 销毁），
 * 本接口只承担运行时句柄职责。
 *
 * @author bigchui
 */
public interface Sandbox {

    /**
     * 容器名称（如 {@code agentx-sandbox-conv_001}）。
     *
     * @return 容器名
     */
    String getContainerName();

    /**
     * workspace 工作目录（容器内绝对路径，如 {@code /workspace}）。
     *
     * @return 工作目录
     */
    String getWorkingDirectory();

    /**
     * 容器是否正在运行。
     *
     * @return 运行中返回 {@code true}
     */
    boolean isRunning();

    /**
     * 获取工具执行后端。
     *
     * <p>工具方法通过此后端在容器内执行命令和文件 I/O。
     *
     * @return 执行后端实例
     */
    ExecutionBackend getExecutionBackend();
}
