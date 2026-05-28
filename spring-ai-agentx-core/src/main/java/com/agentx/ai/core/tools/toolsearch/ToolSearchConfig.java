package com.agentx.ai.core.tools.toolsearch;

/**
 * ToolSearch 搜索配置。
 * <p>
 * 控制延迟工具搜索的行为，包括搜索模式、最大返回数量等。
 * 通过 {@code ReactAgent.builder().deferredTools(config, tools...)} 注入。
 * <p>
 * 不配置时 ToolSearch 不启用，Agent 行为完全不变。
 *
 * <p>使用示例：
 * <pre>{@code
 * // 默认配置（HYBRID 模式，最多返回 5 个工具）
 * ToolSearchConfig.defaults()
 *
 * // 仅关键词模式
 * ToolSearchConfig.builder()
 *     .mode(ToolSearchConfig.Mode.KEYWORD)
 *     .maxResults(3)
 *     .build()
 * }</pre>
 *
 * @param mode        搜索模式：KEYWORD（纯关键词）、LLM（纯 LLM）、HYBRID（先关键词后 LLM）
 * @param maxResults  每次搜索最大返回工具数量，默认 5
 * @author bigchui
 * 
 */
public record ToolSearchConfig(
        Mode mode,
        int maxResults
) {

    /** 默认最大返回数量 */
    public static final int DEFAULT_MAX_RESULTS = 5;

    public ToolSearchConfig {
        if (mode == null) {
            mode = Mode.HYBRID;
        }
        if (maxResults <= 0) {
            maxResults = DEFAULT_MAX_RESULTS;
        }
    }

    /**
     * 搜索模式枚举。
     */
    public enum Mode {
        /** 仅关键词匹配（Jieba 分词 + 打分） */
        KEYWORD,
        /** 仅 LLM 选择（构建精简 catalog，一次 LLM 调用） */
        LLM,
        /** 先关键词，无结果时 LLM 兜底 */
        HYBRID
    }

    /**
     * 返回默认配置（HYBRID 模式，最多 5 个结果）。
     *
     * @return 默认 ToolSearchConfig
     */
    public static ToolSearchConfig defaults() {
        return new ToolSearchConfig(Mode.HYBRID, DEFAULT_MAX_RESULTS);
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
     * Builder 模式构建 ToolSearchConfig。
     */
    public static class Builder {
        private Mode mode = Mode.HYBRID;
        private int maxResults = DEFAULT_MAX_RESULTS;

        /**
         * 搜索模式。
         *
         * @param mode 搜索模式
         * @return Builder
         */
        public Builder mode(Mode mode) {
            this.mode = mode;
            return this;
        }

        /**
         * 每次搜索最大返回工具数量。
         *
         * @param maxResults 最大数量
         * @return Builder
         */
        public Builder maxResults(int maxResults) {
            this.maxResults = maxResults;
            return this;
        }

        /**
         * 构建 ToolSearchConfig。
         *
         * @return ToolSearchConfig 实例
         */
        public ToolSearchConfig build() {
            return new ToolSearchConfig(mode, maxResults);
        }
    }
}
