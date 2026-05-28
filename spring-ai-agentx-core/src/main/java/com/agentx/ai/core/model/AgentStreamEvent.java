package com.agentx.ai.core.model;

import com.agentx.ai.core.exception.AgentErrorCode;

/**
 * Agent 流式事件。
 *
 * 可扩展的 sealed 接口，支持以下事件类型：
 * <ul>
 *   <li>{@link AgentStart} - Agent 开始执行</li>
 *   <li>{@link Thinking} - LLM 思考过程（think 标签内）</li>
 *   <li>{@link Text} - LLM 正常文本输出</li>
 *   <li>{@link ToolStart} - 工具即将执行</li>
 *   <li>{@link ToolEnd} - 工具执行完成</li>
 *   <li>{@link Paused} - 执行暂停，等待外部输入</li>
 *   <li>{@link StageOutput} - 自定义阶段输出（由 {@link StageOutputProvider} 产生）</li>
 *   <li>{@link Error} - LLM 调用异常（重试时发出）</li>
 *   <li>{@link Complete} - Agent 执行完成</li>
 * </ul>
 *
 * @author bigchui
 * 
 */
public sealed interface AgentStreamEvent permits
        AgentStreamEvent.AgentStart,
        AgentStreamEvent.Thinking,
        AgentStreamEvent.Text,
        AgentStreamEvent.ToolStart,
        AgentStreamEvent.ToolEnd,
        AgentStreamEvent.Paused,
        AgentStreamEvent.StageOutput,
        AgentStreamEvent.Error,
        AgentStreamEvent.Complete {

    /**
     * Agent 开始执行。
     */
    record AgentStart() implements AgentStreamEvent {
    }

    /**
     * LLM 思考过程（&lt;think/&gt; 标签内的内容）。
     *
     * @param content 思考内容
     */
    record Thinking(String content) implements AgentStreamEvent {
    }

    /**
     * LLM 正常文本输出。
     *
     * @param content 文本内容
     */
    record Text(String content) implements AgentStreamEvent {
    }

    /**
     * 工具即将执行。
     *
     * @param toolName  工具名称
     * @param toolCallId 工具调用 ID
     * @param arguments 工具调用参数 JSON
     */
    record ToolStart(String toolName, String toolCallId, String arguments) implements AgentStreamEvent {
    }

    /**
     * 工具执行完成。
     *
     * @param toolName  工具名称
     * @param toolCallId 工具调用 ID
     * @param result    工具返回结果
     */
    record ToolEnd(String toolName, String toolCallId, String result) implements AgentStreamEvent {
    }

    /**
     * 执行暂停事件，等待外部输入。
     *
     * @param state 暂停状态
     */
    record Paused(PauseState state) implements AgentStreamEvent {
    }

    /**
     * 自定义阶段输出（由 {@link StageOutputProvider} 产生）。
     *
     * @param stage 阶段名称（如 "reference"、"recommend"）
     * @param data  阶段输出数据
     */
    record StageOutput(String stage, Object data) implements AgentStreamEvent {
    }

    /**
     * LLM 调用异常事件（重试时发出）。
     *
     * @param code    错误码
     * @param message 用户友好提示
     * @param detail  异常详细信息（原始异常消息）
     */
    record Error(AgentErrorCode code, String message, String detail) implements AgentStreamEvent {
    }

    /**
     * Agent 执行完成。
     */
    record Complete() implements AgentStreamEvent {
    }
}
