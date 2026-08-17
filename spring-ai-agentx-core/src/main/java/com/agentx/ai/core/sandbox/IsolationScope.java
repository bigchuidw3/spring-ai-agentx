package com.agentx.ai.core.sandbox;

/**
 * 沙箱隔离级别。
 *
 * <p>决定沙箱容器的作用域键（scope key），影响容器命名与快照覆盖粒度：
 * <ul>
 *   <li>{@link #CONVERSATION} — 以 conversationId 为键，同一会话共享 workspace 快照</li>
 *   <li>{@link #USER} — 以 userId 为键，同一用户跨会话共享 workspace 快照</li>
 * </ul>
 *
 * @author bigchui
 */
public enum IsolationScope {

    /**
     * 会话级隔离，scope key = conversationId
     */
    CONVERSATION,

    /**
     * 用户级隔离，scope key = userId（为 null 时退化为 conversationId）
     */
    USER
}
