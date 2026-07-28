package com.agentx.ai.core.context;

/**
 * 上下文压缩策略配置（v1.0.2 重构）。
 * 控制 6 层渐进式压缩的全部阈值与保护区大小。
 *
 * <h3>单位约定</h3>
 * <ul>
 *   <li><b>token</b>：所有触发/比较类阈值（{@link #tokenThreshold}、{@link #minCompressionTokens}、{@link #largePayloadTokens}），
 *       用 {@link TokenEstimator} 估算（区分中英文，CJK/1.5、ASCII/4.0），与 LLM 上下文窗口对齐</li>
 *   <li><b>字符数</b>：所有 {@code *Chars} 后缀字段，用于截断/预览长度（直观、无需换算）</li>
 *   <li><b>消息条数</b>：{@link #lastKeep}、{@link #minConsecutiveToolMessages}</li>
 * </ul>
 *
 * <pre>{@code
 * ContextPolicy.defaults()
 *
 * ContextPolicy.builder()
 *     .lastKeep(80)
 *     .tokenThreshold(60000)
 *     .largePayloadTokens(4096)
 *     .build()
 * }</pre>
 *
 * @param lastKeep                  保护区大小。最近 N 条消息不被 L1-L4 压缩。默认 50
 * @param msgThreshold              外层门禁的消息数阈值。达到后才进入策略链。默认 100
 * @param tokenThreshold            外层门禁的 token 阈值。默认 90000（≈ 128K × 0.7）
 * @param minConsecutiveToolMessages L1 触发的连续工具消息条数。默认 6（= 3 轮工具调用）
 * @param minCompressionTokens      L1/L4/L6 等策略的最小总 token 数。默认 5000
 * @param largePayloadTokens        L2/L3/L5 单条大消息 token 阈值。默认 2000
 * @param offloadPreviewChars       offload 后保留的预览长度（字符）。默认 200
 * @param toolArgPreviewChars       L1 模板里工具参数预览长度（字符）。默认 100
 * @param toolResultPreviewChars    L1 模板里工具结果预览长度（字符）。默认 100
 * @param maxLlmCompressionCount    L4/L5 单次 compact 最多处理的压缩单元数。默认 3
 * @param currentRoundRatio         L6 目标压缩比（0-1）。默认 0.3
 * @author bigchui
 */
public record ContextPolicy(
        int lastKeep,
        int msgThreshold,
        int tokenThreshold,
        int minConsecutiveToolMessages,
        int minCompressionTokens,
        int largePayloadTokens,
        int offloadPreviewChars,
        int toolArgPreviewChars,
        int toolResultPreviewChars,
        int maxLlmCompressionCount,
        double currentRoundRatio
) {

    public static final int DEFAULT_LAST_KEEP = 50;
    public static final int DEFAULT_MSG_THRESHOLD = 100;
    public static final int DEFAULT_TOKEN_THRESHOLD = 90000;
    public static final int DEFAULT_MIN_CONSECUTIVE_TOOL_MSGS = 6;
    public static final int DEFAULT_MIN_COMPRESSION_TOKENS = 5000;
    public static final int DEFAULT_LARGE_PAYLOAD_TOKENS = 2000;
    public static final int DEFAULT_OFFLOAD_PREVIEW_CHARS = 200;
    public static final int DEFAULT_TOOL_ARG_PREVIEW_CHARS = 100;
    public static final int DEFAULT_TOOL_RESULT_PREVIEW_CHARS = 100;
    public static final int DEFAULT_MAX_LLM_COMPRESSION_COUNT = 3;
    public static final double DEFAULT_CURRENT_ROUND_RATIO = 0.3;

    public static ContextPolicy defaults() {
        return new ContextPolicy(
                DEFAULT_LAST_KEEP,
                DEFAULT_MSG_THRESHOLD,
                DEFAULT_TOKEN_THRESHOLD,
                DEFAULT_MIN_CONSECUTIVE_TOOL_MSGS,
                DEFAULT_MIN_COMPRESSION_TOKENS,
                DEFAULT_LARGE_PAYLOAD_TOKENS,
                DEFAULT_OFFLOAD_PREVIEW_CHARS,
                DEFAULT_TOOL_ARG_PREVIEW_CHARS,
                DEFAULT_TOOL_RESULT_PREVIEW_CHARS,
                DEFAULT_MAX_LLM_COMPRESSION_COUNT,
                DEFAULT_CURRENT_ROUND_RATIO
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int lastKeep = DEFAULT_LAST_KEEP;
        private int msgThreshold = DEFAULT_MSG_THRESHOLD;
        private int tokenThreshold = DEFAULT_TOKEN_THRESHOLD;
        private int minConsecutiveToolMessages = DEFAULT_MIN_CONSECUTIVE_TOOL_MSGS;
        private int minCompressionTokens = DEFAULT_MIN_COMPRESSION_TOKENS;
        private int largePayloadTokens = DEFAULT_LARGE_PAYLOAD_TOKENS;
        private int offloadPreviewChars = DEFAULT_OFFLOAD_PREVIEW_CHARS;
        private int toolArgPreviewChars = DEFAULT_TOOL_ARG_PREVIEW_CHARS;
        private int toolResultPreviewChars = DEFAULT_TOOL_RESULT_PREVIEW_CHARS;
        private int maxLlmCompressionCount = DEFAULT_MAX_LLM_COMPRESSION_COUNT;
        private double currentRoundRatio = DEFAULT_CURRENT_ROUND_RATIO;

        public Builder lastKeep(int v) { this.lastKeep = v; return this; }
        public Builder msgThreshold(int v) { this.msgThreshold = v; return this; }
        public Builder tokenThreshold(int v) { this.tokenThreshold = v; return this; }
        public Builder minConsecutiveToolMessages(int v) { this.minConsecutiveToolMessages = v; return this; }
        public Builder minCompressionTokens(int v) { this.minCompressionTokens = v; return this; }
        public Builder largePayloadTokens(int v) { this.largePayloadTokens = v; return this; }
        public Builder offloadPreviewChars(int v) { this.offloadPreviewChars = v; return this; }
        public Builder toolArgPreviewChars(int v) { this.toolArgPreviewChars = v; return this; }
        public Builder toolResultPreviewChars(int v) { this.toolResultPreviewChars = v; return this; }
        public Builder maxLlmCompressionCount(int v) { this.maxLlmCompressionCount = v; return this; }
        public Builder currentRoundRatio(double v) { this.currentRoundRatio = v; return this; }

        public ContextPolicy build() {
            return new ContextPolicy(
                    lastKeep, msgThreshold, tokenThreshold,
                    minConsecutiveToolMessages, minCompressionTokens,
                    largePayloadTokens, offloadPreviewChars,
                    toolArgPreviewChars, toolResultPreviewChars,
                    maxLlmCompressionCount, currentRoundRatio
            );
        }
    }
}
