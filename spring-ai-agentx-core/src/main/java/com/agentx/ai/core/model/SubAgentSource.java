package com.agentx.ai.core.model;

/**
 * SubAgent 事件来源标识。
 * <p>
 * 携带此标识的 {@link AgentStreamEvent} 来自子 Agent，否则来自主 Agent。
 * 前端通过 {@code source != null} 区分事件来源，通过 {@code subAgentId} 区分并发实例。
 *
 * @param agentName  子 Agent 类型名称（如 "expert"、"analyst"）
 * @param subAgentId 本次调用实例唯一 ID（区分并发场景，如研讨会中多个 analyst 同时运行）
 */
public record SubAgentSource(
        String agentName,
        String subAgentId
) {}
