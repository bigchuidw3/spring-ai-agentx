package com.agentx.ai.core.model;

/**
 * 暂停的工具调用信息。
 *
 * 当 PauseAdvisor 拦截了一个工具调用时，将其封装为 PendingToolCall。
 * 调用方可以通过 {@link #arguments()} 获取 LLM 生成的工具参数（如 AskUserTool 的问题内容），
 * 然后通过 {@link com.agentx.ai.core.agent.ReactAgent#resume(PauseState, java.util.Map)} 提交答案。
 *
 * @param id        工具调用 ID
 * @param name      工具名称
 * @param arguments 工具参数 JSON（包含 LLM 生成的内容）
 * @author bigchui
 * 
 */
public record PendingToolCall(String id, String name, String arguments) {
}
