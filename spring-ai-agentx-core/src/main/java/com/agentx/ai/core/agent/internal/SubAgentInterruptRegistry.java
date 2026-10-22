package com.agentx.ai.core.agent.internal;

import com.agentx.ai.core.model.PauseState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 子 Agent 中断协调器（进程内静态注册表）。
 *
 * <p>解决两个跨 Agent 的问题：
 * <ul>
 *   <li>广播中断：master 中断时，把所有正在运行的同会话子 Agent 一起停掉。</li>
 *   <li>快照收集：子 Agent 各自中断时产出 PauseState，供 master 收集进 {@code children}。</li>
 * </ul>
 *
 * <p>Key 为 conversationId + parentToolCallId（子 Agent 由哪个工具调用派生）。
 */
public final class SubAgentInterruptRegistry {

    private static final class Entry {
        final AgentTaskManager taskManager;
        final AtomicReference<PauseState> snapshot = new AtomicReference<>();

        Entry(AgentTaskManager taskManager) {
            this.taskManager = taskManager;
        }
    }

    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, Entry>> REGISTRY =
            new ConcurrentHashMap<>();

    private SubAgentInterruptRegistry() {
    }

    /**
     * 注册一个正在运行的子 Agent。
     */
    public static void register(String conversationId, String parentToolCallId, AgentTaskManager taskManager) {
        if (conversationId == null || parentToolCallId == null || taskManager == null) {
            return;
        }
        // compute 保证「创建 map + 放入 entry」原子，避免与 takeSnapshots/stopAll 的 remove 竞态丢 entry
        REGISTRY.compute(conversationId, (k, map) -> {
            if (map == null) {
                map = new ConcurrentHashMap<>();
            }
            map.put(parentToolCallId, new Entry(taskManager));
            return map;
        });
    }

    /**
     * 子 Agent 执行结束后注销。
     */
    public static void unregister(String conversationId, String parentToolCallId) {
        if (conversationId == null || parentToolCallId == null) {
            return;
        }
        ConcurrentHashMap<String, Entry> map = REGISTRY.get(conversationId);
        if (map == null) {
            return;
        }
        Entry entry = map.get(parentToolCallId);
        // 被中断过（已发布快照）时保留，等 master takeSnapshots 收集；否则正常移除。
        // 空 map 不主动删（删空 map 与并发 register 竞态会误删新 entry），靠 register 复用 + takeSnapshots/stopAll 清理。
        if (entry != null && entry.snapshot.get() == null) {
            map.remove(parentToolCallId);
        }
    }

    /**
     * 广播中断给该会话下所有正在运行的子 Agent。
     */
    public static void interruptAll(String conversationId, String message) {
        ConcurrentHashMap<String, Entry> map = REGISTRY.get(conversationId);
        if (map == null) {
            return;
        }
        for (Entry entry : new ArrayList<>(map.values())) {
            entry.taskManager.interruptRaw(conversationId, message);
        }
    }

    /**
     * 强制停止该会话下所有正在运行的子 Agent（不存快照，对应 master 的 stopTask）。
     */
    public static void stopAll(String conversationId) {
        ConcurrentHashMap<String, Entry> map = REGISTRY.remove(conversationId);
        if (map == null) {
            return;
        }
        for (Entry entry : map.values()) {
            entry.taskManager.stopTask(conversationId);
        }
    }

    /**
     * 子 Agent 中断后，把它的快照发布到注册表，供 master 收集。
     */
    public static void publishSnapshot(String conversationId, String parentToolCallId, PauseState snapshot) {
        ConcurrentHashMap<String, Entry> map = REGISTRY.get(conversationId);
        if (map == null) {
            return;
        }
        Entry entry = map.get(parentToolCallId);
        if (entry != null) {
            entry.snapshot.set(snapshot);
        }
    }

    /**
     * 收集并清空该会话下所有子 Agent 的快照（master 构建 children 时调用）。
     */
    public static List<PauseState> takeSnapshots(String conversationId) {
        // 原子摘除整个会话条目，避免与 unregister/register 并发产生残留或丢失
        ConcurrentHashMap<String, Entry> map = REGISTRY.remove(conversationId);
        if (map == null) {
            return List.of();
        }
        List<PauseState> result = new ArrayList<>();
        for (Entry entry : map.values()) {
            PauseState snapshot = entry.snapshot.get();
            if (snapshot != null) {
                result.add(snapshot);
            }
        }
        return result;
    }
}
