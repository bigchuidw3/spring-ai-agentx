package com.agentx.ai.core.memory.util;

import com.agentx.ai.core.memory.LongTermMemoryManager;
import com.agentx.ai.core.model.RunnableParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 长期记忆注入器 — 在 Agent 开始执行前按 userId + query 语义检索相关记忆，
 * 格式化为 system prompt 区块。
 *
 * @author bigchui
 */
public class MemoryInjector {

    private static final Logger log = LoggerFactory.getLogger(MemoryInjector.class);

    private final LongTermMemoryManager longTermMemoryManager;

    public MemoryInjector(LongTermMemoryManager longTermMemoryManager) {
        this.longTermMemoryManager = longTermMemoryManager;
    }

    /**
     * 构建长期记忆注入的提示词区块。
     */
    public String buildMemorySection(RunnableParams params, String query) {
        if (longTermMemoryManager == null || params == null || params.getUserId() == null) {
            return "";
        }
        if (query == null || query.isBlank()) {
            return "";
        }
        try {
            List<Document> docs = longTermMemoryManager.searchRelevant(params.getUserId(), query);
            return LongTermMemoryPromptFormatter.formatSection(docs);
        } catch (Exception e) {
            log.error("Long-term memory search failed for userId={}: {}",
                    params.getUserId(), e.getMessage());
            return "";
        }
    }
}
