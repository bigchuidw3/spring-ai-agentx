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
 * 记忆注入器 — 负责对话开始时加载并格式化三层记忆。
 *
 * 从 AgentLoopExecutor 中拆分出的职责：
 * - 用户画像：从 MemoryStore 加载，格式化为 system prompt 区块
 * - 语义检索：从 VectorStore 检索相关历史知识，格式化为 system prompt 区块
 *
 * @author bigchui
 * 
 */
public class MemoryInjector {
    private static final Logger log = LoggerFactory.getLogger(MemoryInjector.class);

    private final MemoryStore memoryStore;
    private final SemanticMemoryManager semanticMemoryManager;
    private final boolean profileMemoryEnabled;

    public MemoryInjector(MemoryStore memoryStore, SemanticMemoryManager semanticMemoryManager, boolean profileMemoryEnabled) {
        this.memoryStore = memoryStore;
        this.semanticMemoryManager = semanticMemoryManager;
        this.profileMemoryEnabled = profileMemoryEnabled;
    }

    /**
     * 构建用户画像注入的提示词区块。
     */
    public String buildMemorySection(RunnableParams params) {
        if (!profileMemoryEnabled || memoryStore == null || params == null || params.getUserId() == null) {
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
     * 构建语义检索结果注入的提示词区块。
     */
    public String buildSemanticSection(RunnableParams params, String query) {
        if (semanticMemoryManager == null || params == null || params.getUserId() == null) {
            return "";
        }
        if (query == null || query.isBlank()) {
            return "";
        }

        try {
            List<Document> docs = semanticMemoryManager.search(params.getUserId(), query, 5);
            return SemanticMemoryPromptFormatter.formatSection(docs);
        } catch (Exception e) {
            log.error("Semantic memory search failed for userId={}: {}",
                    params.getUserId(), e.getMessage());
            return "";
        }
    }
}
