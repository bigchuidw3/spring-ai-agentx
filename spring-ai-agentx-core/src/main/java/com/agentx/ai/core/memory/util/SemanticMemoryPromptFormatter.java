package com.agentx.ai.core.memory.util;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 语义记忆提示词格式化工具。
 *
 * 将 VectorStore 检索到的相关文档格式化为系统提示词文本，
 * 注入到 Agent 的 system prompt 中提供历史知识上下文。
 *
 * @author bigchui
 * 
 */
public class SemanticMemoryPromptFormatter {

    private SemanticMemoryPromptFormatter() {
    }

    /**
     * 将跨会话全局知识格式化为提示词区块（cross_summary）。
     *
     * @param documents 语义检索到的 cross_summary 文档列表
     * @return 格式化后的文本，如果文档为空返回空字符串
     */
    public static String formatCrossSummarySection(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 跨会话全局知识\n");
        sb.append("以下是从历史对话中提炼的、可跨会话复用的知识片段：\n\n");

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            String createdAt = (String) doc.getMetadata().get("created_at");
            String datePrefix = "";
            if (createdAt != null && createdAt.length() >= 10) {
                datePrefix = "[" + createdAt.substring(0, 10) + "] ";
            }
            sb.append(i + 1).append(". ").append(datePrefix).append(doc.getText()).append("\n");
        }

        sb.append("\n注意：以上知识来自历史对话，可能已过时。如与用户当前说法矛盾，以用户当前说法为准。");

        return sb.toString();
    }

    /**
     * 将当前会话历史摘要格式化为提示词区块（session_summary）。
     *
     * @param doc 当前会话的 session_summary 文档（最多一条，可能为 null）
     * @return 格式化后的文本，如果 doc 为 null 或内容为空返回空字符串
     */
    public static String formatSessionSummarySection(Document doc) {
        if (doc == null || doc.getText() == null || doc.getText().isBlank()) {
            return "";
        }

        return "## 当前会话历史摘要\n"
                + "以下是本次会话中超出短期记忆窗口的历史对话摘要：\n\n"
                + doc.getText() + "\n\n"
                + "注意：以上为历史对话的压缩摘要，可能与当前对话状态存在偏差。如与用户当前说法矛盾，以用户当前说法为准。";
    }
}
