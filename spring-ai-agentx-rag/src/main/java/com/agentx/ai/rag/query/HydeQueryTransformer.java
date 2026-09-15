package com.agentx.ai.rag.query;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

import java.util.Objects;

/**
 * HyDE（假设文档嵌入）查询增强器。
 *
 * 先用 LLM 生成一个假设答案，再用假设答案的向量去检索，缓解 query 与文档之间的语义鸿沟。
 * 复用 Spring AI 的 QueryTransformer 接口，提示词可自定义。
 *
 * @author bigchui
 */
public final class HydeQueryTransformer implements QueryTransformer {

    private static final String DEFAULT_PROMPT = """
            你是一个知识助手。请根据用户问题，先写出一个可能的答案（假设答案），这个答案将用于检索相关文档。
            只输出假设答案本身，不要任何解释或前缀。
            用户问题：{query}
            """;

    private final ChatModel chatModel;
    private final String promptTemplate;

    public HydeQueryTransformer(ChatModel chatModel) {
        this(chatModel, DEFAULT_PROMPT);
    }

    public HydeQueryTransformer(ChatModel chatModel, String promptTemplate) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        this.promptTemplate = Objects.requireNonNull(promptTemplate, "promptTemplate");
    }

    @Override
    public Query transform(Query query) {
        Objects.requireNonNull(query, "query");
        String hyde = chatModel.call(promptTemplate.replace("{query}", query.text()));
        String text = hyde == null ? "" : hyde.trim();
        return Query.builder()
                .text(text)
                .history(query.history())
                .context(query.context())
                .build();
    }
}
