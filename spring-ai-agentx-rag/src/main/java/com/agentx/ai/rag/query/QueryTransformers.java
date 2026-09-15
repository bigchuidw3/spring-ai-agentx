package com.agentx.ai.rag.query;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;

import java.util.Objects;

/**
 * QueryTransformer 快速构建工厂。
 *
 * 传入 ChatModel 即得默认配置，适合首次跑通流程。需要自定义提示词或参数时，
 * 直接使用 Spring AI 原 builder 或自建实现。
 *
 * @author bigchui
 */
public final class QueryTransformers {

    private QueryTransformers() {
    }

    /**
     * 问题压缩：结合对话历史消解指代，把追问合成独立 query，同时做精炼。
     *
     * 例：历史"如何创建 Skill" + 追问"那第三种方法呢" → "如何用 npx skills add 安装 Skill"。
     * 多轮对话场景使用。需要传 chatHistory 到 pipeline 的 retrieve 方法才会带上历史。
     */
    public static QueryTransformer compression(ChatModel chatModel) {
        Objects.requireNonNull(chatModel, "chatModel");
        return CompressionQueryTransformer.builder()
                .chatClientBuilder(ChatClient.builder(chatModel))
                .build();
    }

    /**
     * 问题改写：只针对当前 query 本身，去除口语、冗余与歧义，不涉及对话历史。
     *
     * 例："那个 AI 里的技能怎么弄来着" → "如何创建 Claude Code 的 Skill"。
     * 单轮检索场景使用。与 compression 二选一即可，不必同时启用。
     */
    public static QueryTransformer rewrite(ChatModel chatModel) {
        Objects.requireNonNull(chatModel, "chatModel");
        return RewriteQueryTransformer.builder()
                .chatClientBuilder(ChatClient.builder(chatModel))
                .build();
    }

    /**
     * HyDE 假设答案：先让 LLM 生成一个假设答案，用假设答案的向量去检索，
     * 缓解短问题与文档之间的语义鸿沟。
     */
    public static QueryTransformer hyde(ChatModel chatModel) {
        return new HydeQueryTransformer(chatModel);
    }
}
