package com.agentx.ai.core.memory;

import org.springframework.ai.vectorstore.VectorStore;

import java.util.Objects;

/**
 * 长期记忆配置。
 *
 * <p>封装由调用方预先构造好的 {@link VectorStore} 与检索/合并参数，传入
 * {@link com.agentx.ai.core.agent.ReactAgent.Builder#longTermMemory(LongTermMemoryConfig)}。
 * 传入即启用长期记忆；不调用则不启用。
 *
 * <p>VectorStore 的实现（PgVector / Redis / Simple 等）、表名、维度、距离类型都由调用方决定。
 * 框架只负责用它做语义检索、抽取与合并。
 *
 * <p>{@code extractPrompt} / {@code mergePrompt} 允许调用方自定义长期记忆的抽取与合并提示词，
 * 不传则使用框架默认值。
 *
 * @author bigchui
 */
public class LongTermMemoryConfig {

    public static final int DEFAULT_TOP_K = 5;
    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.5;
    public static final int DEFAULT_DEDUP_TOP_K = 3;
    public static final double DEFAULT_DEDUP_THRESHOLD = 0.5;
    public static final int DEFAULT_MAX_CANDIDATES_PER_CALL = 3;

    private final VectorStore vectorStore;
    private final int topK;
    private final double similarityThreshold;
    private final int dedupTopK;
    private final double dedupThreshold;
    private final int maxCandidatesPerCall;
    private final String extractPrompt;
    private final String mergePrompt;

    private LongTermMemoryConfig(Builder b) {
        this.vectorStore = b.vectorStore;
        this.topK = b.topK;
        this.similarityThreshold = b.similarityThreshold;
        this.dedupTopK = b.dedupTopK;
        this.dedupThreshold = b.dedupThreshold;
        this.maxCandidatesPerCall = b.maxCandidatesPerCall;
        this.extractPrompt = b.extractPrompt;
        this.mergePrompt = b.mergePrompt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public VectorStore getVectorStore() {
        return vectorStore;
    }

    public int getTopK() {
        return topK;
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public int getDedupTopK() {
        return dedupTopK;
    }

    public double getDedupThreshold() {
        return dedupThreshold;
    }

    public int getMaxCandidatesPerCall() {
        return maxCandidatesPerCall;
    }

    public String getExtractPrompt() {
        return extractPrompt;
    }

    public String getMergePrompt() {
        return mergePrompt;
    }

    public static class Builder {
        private VectorStore vectorStore;
        private int topK = DEFAULT_TOP_K;
        private double similarityThreshold = DEFAULT_SIMILARITY_THRESHOLD;
        private int dedupTopK = DEFAULT_DEDUP_TOP_K;
        private double dedupThreshold = DEFAULT_DEDUP_THRESHOLD;
        private int maxCandidatesPerCall = DEFAULT_MAX_CANDIDATES_PER_CALL;
        private String extractPrompt;
        private String mergePrompt;

        public Builder vectorStore(VectorStore v) {
            this.vectorStore = v;
            return this;
        }

        public Builder topK(int k) {
            this.topK = k;
            return this;
        }

        public Builder similarityThreshold(double t) {
            this.similarityThreshold = t;
            return this;
        }

        public Builder dedupTopK(int k) {
            this.dedupTopK = k;
            return this;
        }

        public Builder dedupThreshold(double t) {
            this.dedupThreshold = t;
            return this;
        }

        public Builder maxCandidatesPerCall(int n) {
            this.maxCandidatesPerCall = n;
            return this;
        }

        public Builder extractPrompt(String prompt) {
            this.extractPrompt = prompt;
            return this;
        }

        public Builder mergePrompt(String prompt) {
            this.mergePrompt = prompt;
            return this;
        }

        public LongTermMemoryConfig build() {
            Objects.requireNonNull(vectorStore, "vectorStore must not be null");
            return new LongTermMemoryConfig(this);
        }
    }
}
