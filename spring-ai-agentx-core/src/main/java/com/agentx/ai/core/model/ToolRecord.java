package com.agentx.ai.core.model;

/**
 * 工具执行记录。
 *
 * 记录单次工具调用的名称、ID、参数和返回结果，
 * 通过 {@link StageContext} 传递给 {@link StageOutputProvider}。
 *
 * @param toolName   工具名称
 * @param toolCallId 工具调用 ID
 * @param arguments  工具调用参数 JSON
 * @param result     工具返回结果
 * @author bigchui
 * 
 */
public record ToolRecord(
        String toolName,
        String toolCallId,
        String arguments,
        String result
) {
}
