package com.agentx.ai.core.sandbox;

import java.io.InputStream;

/**
 * 沙箱后端 SPI。
 *
 * <p>可插拔的沙箱实现接口，由 {@link SandboxManager} 统一编排。
 * 后端自身携带全部专属配置（镜像、资源限制、工作目录等），
 * 构造方式由各实现类决定（如 {@code DockerBackend.builder()}）。
 *
 * <p>每个后端实现：
 * <ul>
 *   <li>{@link #isAvailable()} — 检测运行环境是否具备（如 docker daemon 是否可达）</li>
 *   <li>{@link #supportsSnapshot()} — 是否需要 workspace 快照（本地后端返回 {@code false}）</li>
 *   <li>{@link #createSandbox(WorkspaceSpec, String)} — 创建并启动新容器</li>
 *   <li>{@link #findExisting(String)} — 查找已运行的容器（用于异常残留复用）</li>
 *   <li>{@link #exportWorkspace(Sandbox)} — 导出 workspace 为 tar 流</li>
 *   <li>{@link #restore(WorkspaceSpec, String, InputStream)} — 从 tar 流恢复容器</li>
 *   <li>{@link #destroy(Sandbox)} — 销毁容器</li>
 * </ul>
 *
 * @author bigchui
 */
public interface SandboxBackend {

    /**
     * 检测后端是否可用（如 docker daemon 是否在运行、CLI 是否在 PATH 上）。
     *
     * <p>框架在注册沙箱 Hook 前调用此方法：不可用且非严格模式时跳过沙箱注册，
     * Agent 退化为宿主执行；严格模式下直接构建失败。
     *
     * @return 可用返回 {@code true}
     */
    boolean isAvailable();

    /**
     * 是否需要 workspace 快照。
     *
     * <p>容器型后端返回 {@code true}（容器销毁前需导出 tar 保住状态）；
     * 本地后端文件本来就在宿主机磁盘上，返回 {@code false}，
     * 框架将跳过快照流程，也不再要求配置快照目录。
     *
     * @return 需要快照返回 {@code true}
     */
    default boolean supportsSnapshot() {
        return true;
    }

    /**
     * 创建并启动新容器，物化 workspace 规格。
     *
     * @param workspaceSpec workspace 物化规格（可为 {@link WorkspaceSpec#empty()}）
     * @param containerName 容器名
     * @return 运行时沙箱实例
     * @throws Exception 创建失败
     */
    Sandbox createSandbox(WorkspaceSpec workspaceSpec, String containerName) throws Exception;

    /**
     * 查找已运行的容器。
     *
     * <p>用于复用场景：上次调用异常退出（如 JVM 强杀）残留的容器，
     * 通过此方法发现并接管。
     *
     * @param containerName 容器名
     * @return 找到返回 {@link Sandbox}；未找到返回 {@code null}
     */
    Sandbox findExisting(String containerName);

    /**
     * 将 workspace 导出为 tar 流。
     *
     * @param sandbox 运行时沙箱实例
     * @return tar 流；后端不支持快照时返回 {@code null}（如本地执行后端，文件已在宿主机）
     * @throws Exception 导出失败
     */
    InputStream exportWorkspace(Sandbox sandbox) throws Exception;

    /**
     * 从 tar 流恢复容器（创建新容器 + 灌入 tar 内容）。
     *
     * @param workspaceSpec workspace 物化规格（在 tar 恢复之后追加物化）
     * @param containerName 容器名
     * @param tarStream     workspace tar 流
     * @return 运行时沙箱实例
     * @throws Exception 恢复失败
     */
    Sandbox restore(WorkspaceSpec workspaceSpec, String containerName, InputStream tarStream) throws Exception;

    /**
     * 销毁容器（stop + remove）。
     *
     * @param sandbox 运行时沙箱实例
     */
    void destroy(Sandbox sandbox);
}
