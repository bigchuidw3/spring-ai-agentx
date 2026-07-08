package com.agentx.ai.core.interrupt;

import com.agentx.ai.core.model.PauseState;

/**
 * Agent 暂停状态存储抽象。
 *
 * <p>统一支撑两类持久化需求：
 * <ul>
 *   <li>HITL 跨进程恢复：用户审批后从外部存储读回状态继续执行</li>
 *   <li>用户主动中断后断点重连：下次会话进入时恢复到中断前状态</li>
 * </ul>
 *
 * <p>实现方仅需关心 {@code conversationId → PauseState} 的读写，
 * 序列化策略由实现自决（InMemory 直接持有对象引用，JDBC 用 JSON 序列化，Redis 用 String 等）。
 *
 * <h2>线程安全契约</h2>
 * <ul>
 *   <li>所有方法必须线程安全（多线程并发调用同 conversationId）</li>
 *   <li>{@link #save(PauseState)} 必须支持同 conversationId 覆盖写入（同一会话可多次中断）</li>
 *   <li>{@link #delete(String)} 必须返回是否实际删除（用于并发恢复保护）</li>
 * </ul>
 *
 * @author bigchui
 */
public interface PauseStateStore {

    /**
     * 保存（覆盖）指定 conversationId 的暂停状态。
     */
    void save(PauseState state);

    /**
     * 按 conversationId 查找暂停状态。
     *
     * @return 状态存在则返回，不存在返回 null
     */
    PauseState findByConversationId(String conversationId);

    /**
     * 检查是否存在未恢复的暂停状态。
     */
    boolean exists(String conversationId);

    /**
     * 删除指定 conversationId 的暂停状态。
     *
     * @return 实际删除返回 true，状态不存在返回 false（用于并发恢复 CAS）
     */
    boolean delete(String conversationId);

    /**
     * 清理已过期的暂停状态。
     *
     * <p>由应用层定时任务调度（如 Spring {@code @Scheduled}），框架自身不启动后台线程。
     * 实现可以选择不实现过期清理（直接空方法）。
     */
    int deleteExpired();
}
