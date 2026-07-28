package com.agentx.ai.core.memory.util;

import com.agentx.ai.core.memory.LongTermMemoryManager;
import com.agentx.ai.core.model.RunnableParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 长期记忆持久化器 — 一次完整 Agent 调用结束后，异步抽取并合并跨会话记忆。
 *
 * 抽取单元是本次调用产生的 original_messages 片段（即 transcript 切片），
 * 由 LongTermMemoryManager 完成 extract → dedup → merge/insert 全流程。
 *
 * @author bigchui
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

    private final LongTermMemoryManager longTermMemoryManager;

    public MemoryPersistor(LongTermMemoryManager longTermMemoryManager) {
        this.longTermMemoryManager = longTermMemoryManager;
    }

    /**
     * 终态成功后异步抽取本次调用 transcript，写入长期记忆。
     *
     * @param params         调用参数（取 userId / conversationId）
     * @param transcript     本次调用产生的原始消息链切片
     */
    public void persist(RunnableParams params, List<Message> transcript) {
        if (longTermMemoryManager == null || params == null || params.getUserId() == null) {
            return;
        }
        if (transcript == null || transcript.isEmpty()) {
            return;
        }
        String userId = params.getUserId();
        String conversationId = params.getConversationId();
        SHARED_EXECUTOR.execute(() -> {
            try {
                longTermMemoryManager.ingestTranscript(userId, conversationId, transcript);
                log.debug("Async long-term memory ingestion completed: userId={}, conversationId={}",
                        userId, conversationId);
            } catch (Exception e) {
                log.error("Async long-term memory ingestion failed: userId={}, conversationId={}, err={}",
                        userId, conversationId, e.getMessage());
            }
        });
    }
}
