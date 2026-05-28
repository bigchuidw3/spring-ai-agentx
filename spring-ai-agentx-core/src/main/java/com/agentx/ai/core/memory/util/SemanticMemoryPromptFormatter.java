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
     * 将检索到的文档格式化为提示词区块。
     *
     * @param documents 检索到的文档列表
     * @return 格式化后的文本，如果文档为空返回空字符串
     */
    public static String formatSection(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 相关历史知识\n");
        sb.append("以下是从历史对话中检索到的、与当前问题语义相关的知识片段：\n\n");

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
}
