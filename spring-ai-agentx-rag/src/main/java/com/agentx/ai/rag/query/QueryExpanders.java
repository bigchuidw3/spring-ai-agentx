package com.agentx.ai.rag.query;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;

import java.util.Objects;

/**
 * QueryExpander 快速构建工厂。
 *
 * 传入 ChatModel 即得默认配置。需要自定义扩展数量或提示词时，
 * 直接使用 Spring AI 原 builder。
 *
 * @author bigchui
 */
public final class QueryExpanders {

    private QueryExpanders() {
    }

    /**
     * 多查询扩展：1→N 生成多个语义变体，包含原问题。默认扩展 3 个变体。
     */
    public static QueryExpander multiQuery(ChatModel chatModel) {
        Objects.requireNonNull(chatModel, "chatModel");
        return MultiQueryExpander.builder()
                .chatClientBuilder(ChatClient.builder(chatModel))
                .numberOfQueries(3)
                .includeOriginal(true)
                .build();
    }
}
