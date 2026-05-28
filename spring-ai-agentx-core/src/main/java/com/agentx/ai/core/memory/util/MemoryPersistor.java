package com.agentx.ai.core.memory.util;

import com.agentx.ai.core.memory.extractor.MemoryExtractor;
import com.agentx.ai.core.memory.semantic.SemanticMemoryManager;
import com.agentx.ai.core.memory.store.MemoryStore;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.stage.ThinkTagParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 记忆持久化器 — 负责对话结束后的异步记忆保存。
 *
 * 从 AgentLoopExecutor 中拆分出的职责：
 * - 用户画像提取：MemoryExtractor 异步提取并保存到 MemoryStore
 * - 语义记忆保存：Q&A 异步存入 VectorStore + 检查摘要阈值
 *
 * @author bigchui
 *
 */
public class MemoryPersistor {

    private static final Logger log = LoggerFactory.getLogger(MemoryPersistor.class);

    /** 静态共享 daemon 线程池，避免每次创建 AgentLoopExecutor 都泄漏一个线程池 */
    private static final ExecutorService SHARED_EXECUTOR = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(),
            r -> {
                Thread t = new Thread(r, "agent-memory");
                t.setDaemon(true);
                return t;
            });

    private final MemoryStore memoryStore;
    private final ChatModel chatModel;
    private final SemanticMemoryManager semanticMemoryManager;
    private final boolean profileMemoryEnabled;

    public MemoryPersistor(MemoryStore memoryStore, ChatModel chatModel,
                           SemanticMemoryManager semanticMemoryManager,
                           boolean profileMemoryEnabled, ExecutorService executor) {
        this.memoryStore = memoryStore;
        this.chatModel = chatModel;
        this.semanticMemoryManager = semanticMemoryManager;
        this.profileMemoryEnabled = profileMemoryEnabled;
    }

    /**
     * 向后兼容的构造函数（忽略 executor 参数，使用静态共享线程池）。
     */
    public MemoryPersistor(MemoryStore memoryStore, ChatModel chatModel,
                           SemanticMemoryManager semanticMemoryManager,
                           boolean profileMemoryEnabled) {
        this.memoryStore = memoryStore;
        this.chatModel = chatModel;
        this.semanticMemoryManager = semanticMemoryManager;
        this.profileMemoryEnabled = profileMemoryEnabled;
    }

    /**
     * 对话结束后执行所有异步记忆持久化任务。
     *
     * @param params         调用参数
     * @param question       用户问题
     * @param answer         助手回答
     * @param conversationId 会话 ID
     */
    public void persist(RunnableParams params, String question, String answer, String conversationId) {
        // 记忆提取和语义保存不需要 think 内容，统一剥离
        String cleanAnswer = ThinkTagParser.stripThinkTags(answer);
        extractMemoriesIfEnabled(params, question, cleanAnswer);
        saveSemanticMemoryIfEnabled(params, question, cleanAnswer, conversationId);
    }

    /**
     * 异步提取用户画像到 MemoryStore。
     */
    private void extractMemoriesIfEnabled(RunnableParams params, String question, String answer) {
        if (!profileMemoryEnabled || memoryStore == null || chatModel == null
                || params == null || params.getUserId() == null) {
            return;
        }
        if (question == null || question.isEmpty() || answer == null || answer.isEmpty()) {
            return;
        }

        String userId = params.getUserId();
        SHARED_EXECUTOR.execute(() -> {
            try {
                MemoryExtractor extractor = new MemoryExtractor(chatModel, memoryStore);
                extractor.extractAndSave(userId, question, answer);
                log.debug("Async memory extraction completed for userId={}", userId);
            } catch (Exception e) {
                log.error("Async memory extraction failed for userId={}: {}",
                        userId, e.getMessage());
            }
        });
    }

    /**
     * 异步保存 Q&A 到 VectorStore 并检查摘要阈值。
     */
    private void saveSemanticMemoryIfEnabled(RunnableParams params, String question,
                                              String answer, String conversationId) {
        if (semanticMemoryManager == null || params == null || params.getUserId() == null) {
            return;
        }
        if (question == null || question.isEmpty() || answer == null || answer.isEmpty()) {
            return;
        }

        String userId = params.getUserId();
        SHARED_EXECUTOR.execute(() -> {
            try {
                semanticMemoryManager.saveQaPair(userId, question, answer, conversationId);
                semanticMemoryManager.checkAndSummarize(userId);
                log.debug("Semantic memory save completed for userId={}", userId);
            } catch (Exception e) {
                log.error("Semantic memory save failed for userId={}: {}",
                        userId, e.getMessage());
            }
        });
    }
}
