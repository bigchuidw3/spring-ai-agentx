package com.agentx.ai.rag.pipeline;

import org.springframework.ai.document.Document;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * RAG 检索工具。
 *
 * 把 RagPipeline 封装成 Spring AI ToolCallback，注册到 ReactAgent 等智能体后，
 * 由 LLM 自主决定何时检索、用什么关键词，实现 Agentic RAG。
 *
 * @author bigchui
 */
public final class RagRetrievalTool {

    private static final String TOOL_NAME = "ragSearch";

    private static final String TOOL_DESCRIPTION = """
            检索本地知识库，返回与搜索语句相关的文档片段。
            注意：
            1. query 必须是独立完整的搜索语句，自行消解代词，不要传"它"、"这个"之类的指代
            2. 问题涉及知识库文档内容时才调用本工具
            3. 首次结果不相关时，可改写关键词后再次调用
            """;

    private RagRetrievalTool() {
    }

    public static ToolCallback of(RagPipeline pipeline) {
        return FunctionToolCallback.builder(TOOL_NAME,
                        (RagQuery request) -> joinTexts(pipeline.retrieve(request.query(), List.of())))
                .description(TOOL_DESCRIPTION)
                .inputType(RagQuery.class)
                .build();
    }

    private static String joinTexts(List<Document> documents) {
        StringBuilder result = new StringBuilder();
        for (Document document : documents) {
            if (!result.isEmpty()) {
                result.append("\n\n");
            }
            result.append(document.getText());
        }
        return result.toString();
    }

    /**
     * 工具入参。
     */
    public record RagQuery(String query) {
    }
}
