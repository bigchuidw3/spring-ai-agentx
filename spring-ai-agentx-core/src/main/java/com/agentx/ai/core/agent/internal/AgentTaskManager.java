package com.agentx.ai.core.agent.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Agent 任务管理器。
 * <p>
 * 用于管理流式输出的停止和中断，防止同一会话并发执行。
 * 支持任务注册、并发控制、流式停止、用户主动中断和资源清理。
 *
 * <h2>stopTask vs interrupt</h2>
 * <ul>
 *   <li>{@link #stopTask(String)} — 强制停止，丢弃所有运行时状态。
 *       适用于用户点击"停止"且不需要后续恢复的场景</li>
 *   <li>{@link #interrupt(String, String)} — 用户主动中断，
 *       会调用 AgentLoopExecutor 注册的快照回调保存 PauseState 后再停止。
 *       适用于"暂停，下次继续"的场景</li>
 * </ul>
 *
 * @author bigchui
 */
public class AgentTaskManager {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskManager.class);

    private final Map<String, TaskInfo> taskMap = new ConcurrentHashMap<>();

    public static class TaskInfo {
        // 通配类型
        private final Sinks.Many<?> sink;
        private volatile Disposable disposable;
        private final long createTime;

        /**
         * 中断请求（仅 interrupt 路径设置，stopTask 不设置）
         */
        private volatile InterruptRequest interruptRequest;
        /**
         * 中断快照回调（由 AgentLoopExecutor 在每轮 scheduleRound 注册）
         */
        private volatile Consumer<String> interruptHandler;

        TaskInfo(Sinks.Many<?> sink) {
            this.sink = sink;
            this.createTime = System.currentTimeMillis();
        }

        public Sinks.Many<?> getSink() {
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

        public InterruptRequest getInterruptRequest() {
            return interruptRequest;
        }

        /**
         * 注册中断快照回调。AgentLoopExecutor 在每轮开始时注册，
         * 捕获当前 messages / sink / execCtx 等上下文用于构建 PauseState。
         */
        public void setInterruptHandler(Consumer<String> handler) {
            this.interruptHandler = handler;
        }

        /**
         * 设置中断请求标志，并触发已注册的快照回调。
         * 返回值表示是否成功触发回调（true = 已回调，false = 无回调注册）。
         */
        public boolean triggerInterrupt(String message) {
            this.interruptRequest = new InterruptRequest(message, System.currentTimeMillis());
            Consumer<String> h = this.interruptHandler;
            if (h != null) {
                h.accept(message);
                return true;
            }
            return false;
        }
    }

    /**
     * 中断请求记录。
     */
    public static class InterruptRequest {
        private final String message;
        private final long requestedAt;

        public InterruptRequest(String message, long requestedAt) {
            this.message = message;
            this.requestedAt = requestedAt;
        }

        public String getMessage() {
            return message;
        }

        public long getRequestedAt() {
            return requestedAt;
        }
    }

    public TaskInfo registerTask(String conversationId, Sinks.Many<?> sink) {
        TaskInfo newTask = new TaskInfo(sink);
        TaskInfo existing = taskMap.putIfAbsent(conversationId, newTask);
        if (existing != null) {
            log.warn("Task already exists for conversation: {}", conversationId);
            return null;
        }
        log.debug("Registered task for conversation: {}", conversationId);
        return newTask;
    }

    public void setDisposable(String conversationId, Disposable disposable) {
        TaskInfo taskInfo = taskMap.get(conversationId);
        if (taskInfo != null) {
            taskInfo.setDisposable(disposable);
        } else {
            // task 已被 stopTask 移除（如客户端断连），直接 dispose 防止泄漏
            if (disposable != null && !disposable.isDisposed()) {
                disposable.dispose();
                log.debug("Task already removed, disposed orphaned subscription: {}", conversationId);
            }
        }
    }

    /**
     * 注册中断快照回调。委托给当前 TaskInfo。
     */
    public void setInterruptHandler(String conversationId, Consumer<String> handler) {
        TaskInfo taskInfo = taskMap.get(conversationId);
        if (taskInfo != null) {
            taskInfo.setInterruptHandler(handler);
        }
    }

    /**
     * 用户主动中断：触发快照回调 + dispose 订阅。
     *
     * <p>若 AgentLoopExecutor 已注册中断处理器：先调用回调构建并发射 PauseState，
     * 然后 dispose 当前 LLM 订阅停止上游。回调内部负责发射 Paused 事件和 tryEmitComplete。
     *
     * <p>若未注册（极少见，如 interrupt 在首轮 scheduleRound 之前调用）：
     * 直接 dispose + tryEmitComplete，等同 stopTask。
     *
     * @param conversationId 会话 ID
     * @param message        中断说明消息（可为 null）
     * @return true 如果任务存在并已触发中断
     */
    public boolean interrupt(String conversationId, String message) {
        TaskInfo taskInfo = taskMap.get(conversationId);
        if (taskInfo == null) {
            log.warn("No running task to interrupt for conversation: {}", conversationId);
            return false;
        }

        // 1. 先 dispose 当前订阅，阻止后续 chunk 流入 sink
        //    （volatile 字段已记录最后阶段，快照可从中读取）
        Disposable disposable = taskInfo.getDisposable();
        if (disposeQuietly(disposable)) {
            log.debug("Interrupted in-flight subscription: conversationId={}", conversationId);
        }

        // 2. 触发已注册的快照回调：构建 PauseState、发射 Paused 事件、tryEmitComplete
        boolean handled = taskInfo.triggerInterrupt(message);
        if (!handled) {
            log.warn("No interrupt handler registered for conversation {}, fallback complete sink: {}",
                    conversationId, message);
            // 无回调（极少见，如首轮 scheduleRound 之前）— 直接 complete sink
            Sinks.Many<?> sink = taskInfo.getSink();
            if (sink != null) {
                sink.tryEmitComplete();
            }
        }
        return true;
    }

    /**
     * 强制停止任务，丢弃所有运行时状态。
     */
    public boolean stopTask(String conversationId) {
        // 原子操作：remove 同时拿回并删除，防止 get 和 remove 之间新任务被误删
        TaskInfo taskInfo = taskMap.remove(conversationId);
        if (taskInfo == null) {
            log.warn("No running task for conversation: {}", conversationId);
            return false;
        }

        try {
            Disposable disposable = taskInfo.getDisposable();
            if (disposable != null && !disposable.isDisposed()) {
                disposable.dispose();
                log.debug("Disposed underlying call for conversation: {}", conversationId);
            }

            var sink = taskInfo.getSink();
            if (sink != null) {
                sink.tryEmitComplete();
                log.debug("Completed stream output for conversation: {}", conversationId);
            }

            return true;
        } catch (Exception e) {
            log.error("Failed to stop task for conversation: {}", conversationId, e);
            return false;
        }
    }

    public void removeTask(String conversationId) {
        TaskInfo removed = taskMap.remove(conversationId);
        if (removed != null) {
            log.debug("Removed task for conversation: {}", conversationId);
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

    private static boolean disposeQuietly(Disposable disposable) {
        if (disposable == null || disposable.isDisposed()) {
            return false;
        }
        try {
            disposable.dispose();
            return true;
        } catch (Exception e) {
            log.debug("Ignoring dispose error: {}", e.getMessage());
            return false;
        }
    }
}
