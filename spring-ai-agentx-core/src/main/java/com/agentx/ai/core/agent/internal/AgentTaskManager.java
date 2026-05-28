package com.agentx.ai.core.agent.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 任务管理器。
 * <p>
 * 用于管理流式输出的停止和中断，防止同一会话并发执行。
 * 支持任务注册、并发控制、流式停止和资源清理。
 *
 * @author bigchui
 *
 */
public class AgentTaskManager {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskManager.class);

    private final Map<String, TaskInfo> taskMap = new ConcurrentHashMap<>();

    public static class TaskInfo {
        private final Sinks.Many<ChatResponse> sink;
        private Disposable disposable;
        private final long createTime;

        TaskInfo(Sinks.Many<ChatResponse> sink) {
            this.sink = sink;
            this.createTime = System.currentTimeMillis();
        }

        public Sinks.Many<ChatResponse> getSink() {
            return sink;
        }

        public Disposable getDisposable() {
            return disposable;
        }

        public void setDisposable(Disposable disposable) {
            this.disposable = disposable;
        }

        public long getCreateTime() {
            return createTime;
        }
    }

    public TaskInfo registerTask(String conversationId, Sinks.Many<ChatResponse> sink) {
        TaskInfo existing = taskMap.putIfAbsent(conversationId, new TaskInfo(sink));
        if (existing != null) {
            log.warn("Task already exists for conversation: {}", conversationId);
            return null;
        }
        log.info("Registered task for conversation: {}", conversationId);
        return existing != null ? existing : taskMap.get(conversationId);
    }

    public void setDisposable(String conversationId, Disposable disposable) {
        TaskInfo taskInfo = taskMap.get(conversationId);
        if (taskInfo != null) {
            taskInfo.setDisposable(disposable);
        }
    }

    public boolean stopTask(String conversationId) {
        TaskInfo taskInfo = taskMap.get(conversationId);
        if (taskInfo == null) {
            log.warn("No running task for conversation: {}", conversationId);
            return false;
        }

        try {
            Disposable disposable = taskInfo.getDisposable();
            if (disposable != null && !disposable.isDisposed()) {
                disposable.dispose();
                log.info("Disposed underlying call for conversation: {}", conversationId);
            }

            var sink = taskInfo.getSink();
            if (sink != null) {
                sink.tryEmitComplete();
                log.info("Completed stream output for conversation: {}", conversationId);
            }

            taskMap.remove(conversationId);
            return true;
        } catch (Exception e) {
            log.error("Failed to stop task for conversation: {}", conversationId, e);
            return false;
        }
    }

    public void removeTask(String conversationId) {
        TaskInfo removed = taskMap.remove(conversationId);
        if (removed != null) {
            log.info("Removed task for conversation: {}", conversationId);
        }
    }

    public boolean hasRunningTask(String conversationId) {
        return taskMap.containsKey(conversationId);
    }

    public TaskInfo getTask(String conversationId) {
        return taskMap.get(conversationId);
    }

    public int getTaskCount() {
        return taskMap.size();
    }
}
