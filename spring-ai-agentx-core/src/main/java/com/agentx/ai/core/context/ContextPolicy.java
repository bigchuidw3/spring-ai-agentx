package com.agentx.ai.core.context;

import java.util.HashSet;
import java.util.Set;

/**
 * 上下文压缩策略配置。
 * <p>
 * 控制 Agent 循环中上下文压缩的行为，包括 token 阈值、保留数量、保护工具列表等。
 * 通过 {@code ReactAgent.builder().contextPolicy(ContextPolicy)} 注入。
 * <p>
 * 不配置时上下文压缩不启用，Agent 行为完全不变。
 *
 * <p>使用示例：
 * <pre>{@code
 * // 默认配置
 * ContextPolicy.defaults()
 *
 * // 自定义配置
 * ContextPolicy.builder()
 *     .tokenThreshold(30000)
 *     .keepRecentTools(5)
 *     .protectedTools("my_protected_tool")  // SkillsTool 已内置保护，无需手动添加
 *     .build()
 * }</pre>
 *
 * @param tokenThreshold  触发 auto_compact 的 token 估算阈值，默认 60000
 * @param keepRecentTools micro_compact 保留最近 N 轮工具调用的完整内容，默认 4
 * @param maxToolLength   ToolResponse 内容和 ToolCall 参数的统一压缩阈值（字符），
 *                        超过时替换为占位符，默认 200。设为 0 不截断
 * @param protectedTools  受保护的工具名称集合（不包含内置保护工具）。
 *                        内置保护工具（SkillsTool）会自动加入，无需手动配置。
 *                        这些工具的响应和参数不会被压缩。
 * @author bigchui
 * 
 */
public record ContextPolicy(
        int tokenThreshold,
        int keepRecentTools,
        int maxToolLength,
        Set<String> protectedTools
) {

    /** 默认 token 阈值 */
    public static final int DEFAULT_TOKEN_THRESHOLD = 60000;
    /** 默认保留最近工具调用轮数 */
    public static final int DEFAULT_KEEP_RECENT_TOOLS = 4;
    /** 默认工具内容压缩阈值（ToolResponse 和 ToolCall args 统一使用） */
    public static final int DEFAULT_MAX_TOOL_LENGTH = 200;
    /** 内置保护工具：SkillsTool */
    private static final Set<String> BUILTIN_PROTECTED_TOOLS = Set.of("Skill");

    public ContextPolicy {
        // 合并用户配置的保护工具 + 内置保护工具
        Set<String> allProtected = new HashSet<>(BUILTIN_PROTECTED_TOOLS);
        if (protectedTools != null) {
            allProtected.addAll(protectedTools);
        }
        protectedTools = Set.copyOf(allProtected);
    }

    /**
     * 返回默认配置。
     *
     * @return 默认上下文压缩策略
     */
    public static ContextPolicy defaults() {
        return new ContextPolicy(
                DEFAULT_TOKEN_THRESHOLD,
                DEFAULT_KEEP_RECENT_TOOLS,
                DEFAULT_MAX_TOOL_LENGTH,
                Set.of()
        );
    }

    /**
     * 创建 Builder。
     *
     * @return Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 检查工具是否受保护（不被压缩）。
     *
     * @param toolName 工具名称
     * @return 是否受保护
     */
    public boolean isProtected(String toolName) {
        return protectedTools.contains(toolName);
    }

    /**
     * Builder 模式构建 ContextPolicy。
     */
    public static class Builder {
        private int tokenThreshold = DEFAULT_TOKEN_THRESHOLD;
        private int keepRecentTools = DEFAULT_KEEP_RECENT_TOOLS;
        private int maxToolLength = DEFAULT_MAX_TOOL_LENGTH;
        private Set<String> protectedTools = Set.of();

        /**
         * 触发 auto_compact 的 token 阈值。
         *
         * @param v token 阈值
         * @return Builder
         */
        public Builder tokenThreshold(int v) {
            this.tokenThreshold = v;
            return this;
        }

        /**
         * micro_compact 保留最近 N 轮工具调用的完整内容。
         *
         * @param v 保留轮数
         * @return Builder
         */
        public Builder keepRecentTools(int v) {
            this.keepRecentTools = v;
            return this;
        }

        /**
         * ToolResponse 内容和 ToolCall 参数的统一压缩阈值（字符）。
         * 超过时替换为占位符，设为 0 不截断。
         *
         * @param v 压缩阈值
         * @return Builder
         */
        public Builder maxToolLength(int v) {
            this.maxToolLength = v;
            return this;
        }

        /**
         * 受保护的工具名称集合。
         * <p>
         * 这些工具的 ToolResponse 和 ToolCall 参数不会被 micro_compact 替换。
         * SkillsTool 已内置保护，无需手动添加。
         *
         * @param tools 工具名称
         * @return Builder
         */
        public Builder protectedTools(String... tools) {
            this.protectedTools = Set.of(tools);
            return this;
        }

        /**
         * 构建 ContextPolicy。
         *
         * @return ContextPolicy 实例
         */
        public ContextPolicy build() {
            return new ContextPolicy(
                    tokenThreshold, keepRecentTools,
                    maxToolLength, protectedTools
            );
        }
    }
}
