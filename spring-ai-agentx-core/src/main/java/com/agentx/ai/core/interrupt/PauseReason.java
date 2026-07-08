package com.agentx.ai.core.interrupt;

/**
 * Agent 进入暂停状态的原因。
 *
 * <p>统一描述 HITL（工具执行前人工审批）与用户主动中断两种场景，
 * 用于 {@link com.agentx.ai.core.model.AgentStreamEvent.Paused} 事件携带，
 * 让前端、调用方按不同原因展示不同 UI 与交互。
 *
 * <ul>
 *   <li>{@link #HITL_TOOL_REQUEST} — PauseAdvisor 在工具执行前拦截（如 AskUser 工具、需审批的副作用工具），
 *       此时工具尚未执行，恢复时由用户提供答案或确认</li>
 *   <li>{@link #USER_INTERRUPT} — 用户主动触发 {@code interrupt}，可能是 LLM 流式中或工具执行中。
 *       工具可能已部分执行副作用，恢复时按 ResumePolicy 处理 pendingToolCalls</li>
 * </ul>
 *
 * @author bigchui
 */
public enum PauseReason {

    /**
     * HITL 拦截：工具执行前由 PauseAdvisor 触发。
     */
    HITL_TOOL_REQUEST,

    /**
     * 用户主动中断：通过 {@code ReactAgent.interrupt(convId, msg)} 触发。
     */
    USER_INTERRUPT,
}
