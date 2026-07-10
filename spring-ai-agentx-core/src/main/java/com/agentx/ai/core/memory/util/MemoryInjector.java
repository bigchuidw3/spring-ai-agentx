package com.agentx.ai.core.memory.util;

import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.memory.semantic.SemanticMemoryManager;
import com.agentx.ai.core.memory.store.MemoryStore;
import com.agentx.ai.core.memory.model.MemoryItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 记忆注入器 — 负责对话开始时加载并格式化记忆区块。
 *
 * 从 AgentLoopExecutor 中拆分出的职责：
 * - 跨会话全局知识（cross_summary）：语义检索 + 格式化注入
 * - 当前会话历史摘要（session_summary）：精确查找 + 格式化注入
 * - 用户画像（agentx_memory）：已弃用，代码保留但不推荐使用
 *
 * @author bigchui
 *
 */
public class MemoryInjector {
    private static final Logger log = LoggerFactory.getLogger(MemoryInjector.class);

    private final MemoryStore memoryStore;
    private final SemanticMemoryManager semanticMemoryManager;
    private final boolean enableProfileMemory;

    public MemoryInjector(MemoryStore memoryStore, SemanticMemoryManager semanticMemoryManager, boolean enableProfileMemory) {
        this.memoryStore = memoryStore;
        this.semanticMemoryManager = semanticMemoryManager;
        this.enableProfileMemory = enableProfileMemory;
    }

    /**
     * 构建用户画像注入的提示词区块。
     */
    public String buildMemorySection(RunnableParams params) {
        if (!enableProfileMemory || memoryStore == null || params == null || params.getUserId() == null) {
            return "";
        }

        String userId = params.getUserId();
        List<MemoryItem> memories = memoryStore.findByUserId(userId);
        if (memories.isEmpty()) {
            return "";
        }

        return MemoryPromptFormatter.formatSection(memories);
    }

    /**
     * 构建跨会话全局知识注入的提示词区块（cross_summary）。
     * <p>
     * 按 userId + query 语义检索相关的跨会话知识摘要，
     * 格式化为独立区块注入到 system prompt。
     */
    public String buildCrossSummarySection(RunnableParams params, String query) {
        if (semanticMemoryManager == null || params == null || params.getUserId() == null) {
            return "";
        }
        if (query == null || query.isBlank()) {
            return "";
        }

        try {
            List<Document> docs = semanticMemoryManager.searchCrossSummary(params.getUserId(), query, 5);
            return SemanticMemoryPromptFormatter.formatCrossSummarySection(docs);
        } catch (Exception e) {
            log.error("Cross-summary search failed for userId={}: {}",
                    params.getUserId(), e.getMessage());
            return "";
        }
    }

    /**
     * 构建当前会话历史摘要注入的提示词区块（session_summary）。
     * <p>
     * 按 conversationId 精确查找（最多一条），不需要语义检索。
     * 格式化为独立区块注入到 system prompt。
     */
    public String buildSessionSummarySection(RunnableParams params) {
        if (semanticMemoryManager == null || params == null || params.getConversationId() == null) {
            return "";
        }

        try {
            Document doc = semanticMemoryManager.getSessionSummary(params.getConversationId());
            return SemanticMemoryPromptFormatter.formatSessionSummarySection(doc);
        } catch (Exception e) {
            log.error("Session summary lookup failed for conversationId={}: {}",
                    params.getConversationId(), e.getMessage());
            return "";
        }
    }
}
