package com.agentx.ai.rag.pipeline;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * RAG 检索编排 SPI。
 *
 * 把用户问题和对话历史变成检索结果文档。框架提供 DefaultRagPipeline
 * 固定骨架实现，调用方也可基于此接口完全自定义编排。
 *
 * @author bigchui
 */
public interface RagPipeline {

    /**
     * 检索与问题相关的文档。
     *
     * @param question    用户问题
     * @param chatHistory 对话历史，多轮对话时用于指代消解，可为空
     * @return 检索结果文档
     */
    List<Document> retrieve(String question, List<Message> chatHistory);
}
