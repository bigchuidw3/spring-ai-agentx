package com.agentx.ai.core.interrupt;

/**
 * 中断安全点 — 标记 Agent 执行流程中可被安全中断的位置。
 *
 * <p>每个安全点对应 ReAct 循环里的一个"检查站"，进入检查站时通过
 * {@link com.agentx.ai.core.agent.internal.InterruptContext#enterLlmStreaming()}
 * 或 {@link com.agentx.ai.core.agent.internal.InterruptContext#enterToolExecution} 切换状态。
 * 中断触发时，{@link com.agentx.ai.core.model.PauseState} 携带当前安全点信息，
 * 调用方/前端可据此判断副作用风险等级与恢复策略。
 *
 * <h2>状态机（单向不可回归）</h2>
 * <pre>
 * INIT ──scheduleRound 首次调用──▶ LLM_STREAMING ⇄ TOOL_EXECUTION
 * </pre>
 * <p>INIT 是 InterruptContext 创建后、首次进入安全点之前的初始状态，流一旦启动不再回到 INIT。
 *
 * <h2>副作用风险分级</h2>
 * <ul>
 *   <li>{@link #INIT} — 流未启动。无副作用，最安全</li>
 *   <li>{@link #LLM_STREAMING} — 调用 LLM 前插入的安全点。
 *       副作用窗口：仅消耗 token，无外部副作用</li>
 *   <li>{@link #TOOL_EXECUTION} — 调用工具前插入的安全点。
 *       副作用窗口：工具可能已部分执行（如 MCP 调用、DB 写入）。
 *       中断时 pendingToolCalls 含正在执行的工具调用，恢复重放需工具自身保证幂等</li>
 * </ul>
 *
 * @author bigchui
 */
public enum SafePoint {

    /**
     * 初始状态：InterruptContext 已创建，但流式循环尚未启动。
     * <p>仅在 InterruptContext 构造时设置，流一旦启动不再回到此状态。
     */
    INIT,

    /**
     * 安全点：在调用 LLM 之前插入。
     * <p>中断触发时仅记录当前所在位置，不缓存额外状态；
     * 恢复时基于 messages 重新调 LLM 即可。
     */
    LLM_STREAMING,

    /**
     * 安全点：在调用工具之前插入（含 SubAgent 执行）。
     * <p>进入此安全点时，待执行的工具调用列表会一并缓存到 pendingToolCalls；
     * 中断触发时快照携带该列表，但工具可能已部分执行，恢复需谨慎（建议工具幂等）。
     */
    TOOL_EXECUTION,
}
