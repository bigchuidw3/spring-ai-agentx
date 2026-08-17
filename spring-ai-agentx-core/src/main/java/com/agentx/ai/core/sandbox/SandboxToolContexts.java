package com.agentx.ai.core.sandbox;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.lang.Nullable;

/**
 * 从 Spring AI {@link ToolContext} 提取 {@link ExecutionBackend} 的静态工具方法。
 *
 * <p>所有沙箱感知工具（{@code FileSystemTools}、{@code GrepTool}）通过本类
 * 统一从 ToolContext 获取执行后端，避免重复的类型检查代码。
 *
 * <p>ToolContext 的 key 约定为 {@code "executionBackend"}，
 * 由 {@code ToolCallExecutor.buildToolContext} 写入。
 *
 * @author bigchui
 */
public final class SandboxToolContexts {

    /** ToolContext 中 ExecutionBackend 的 key */
    public static final String EXECUTION_BACKEND_KEY = "executionBackend";

    private SandboxToolContexts() {
    }

    /**
     * 从 ToolContext 提取 ExecutionBackend。
     *
     * @param toolContext Spring AI 工具上下文（可为 {@code null}）
     * @return 后端实例；不存在或类型不匹配时返回 {@code null}
     */
    @Nullable
    public static ExecutionBackend extract(@Nullable ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(EXECUTION_BACKEND_KEY);
        if (value instanceof ExecutionBackend backend) {
            return backend;
        }
        return null;
    }
}
