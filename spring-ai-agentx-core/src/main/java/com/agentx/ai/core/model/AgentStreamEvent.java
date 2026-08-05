package com.agentx.ai.core.model;

import com.agentx.ai.core.exception.AgentErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Agent 流式事件。
 * <p>
 * 使用 Jackson 多态序列化，每个事件自动携带 {@code "type"} 字段用于类型鉴别。
 * 例如：{@code {"type":"Text","content":"你好"}}
 *
 * <p>子 Agent 事件携带 {@link SubAgentSource} 标识，主 Agent 事件不携带（source 为 null，
 * Jackson 不序列化 null 字段，向前兼容）。
 *
 * 可扩展的 sealed 接口，支持以下事件类型：
 * <ul>
 *   <li>{@link Thinking} - LLM 思考过程（think 标签内）</li>
 *   <li>{@link Text} - LLM 正常文本输出</li>
 *   <li>{@link ToolStart} - 工具即将执行</li>
 *   <li>{@link ToolEnd} - 工具执行完成</li>
 *   <li>{@link Paused} - 执行暂停，等待外部输入（含 HITL 与 USER_INTERRUPT 两种原因）</li>
 *   <li>{@link Error} - LLM 调用异常（重试时发出）</li>
 *   <li>{@link Complete} - Agent 执行完成</li>
 * </ul>
 *
 * @author bigchui
 *
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AgentStreamEvent.Thinking.class, name = "Thinking"),
    @JsonSubTypes.Type(value = AgentStreamEvent.Text.class, name = "Text"),
    @JsonSubTypes.Type(value = AgentStreamEvent.ToolStart.class, name = "ToolStart"),
    @JsonSubTypes.Type(value = AgentStreamEvent.ToolEnd.class, name = "ToolEnd"),
    @JsonSubTypes.Type(value = AgentStreamEvent.Paused.class, name = "Paused"),
    @JsonSubTypes.Type(value = AgentStreamEvent.Error.class, name = "Error"),
    @JsonSubTypes.Type(value = AgentStreamEvent.Complete.class, name = "Complete")
})
public sealed interface AgentStreamEvent permits
        AgentStreamEvent.Thinking,
        AgentStreamEvent.Text,
        AgentStreamEvent.ToolStart,
        AgentStreamEvent.ToolEnd,
        AgentStreamEvent.Paused,
        AgentStreamEvent.Error,
        AgentStreamEvent.Complete {

    /**
     * LLM 思考过程（&lt;think/&gt; 标签内的内容）。
     *
     * @param content 思考内容
     * @param source  事件来源（null 表示主 Agent）
     */
    record Thinking(String content, SubAgentSource source) implements AgentStreamEvent {
        public Thinking(String content) { this(content, null); }
    }

    /**
     * LLM 正常文本输出。
     *
     * @param content 文本内容
     * @param source  事件来源（null 表示主 Agent）
     */
    record Text(String content, SubAgentSource source) implements AgentStreamEvent {
        public Text(String content) { this(content, null); }
    }

    /**
     * 工具即将执行。
     *
     * @param toolName  工具名称
     * @param toolCallId 工具调用 ID
     * @param arguments 工具调用参数 JSON
     * @param source    事件来源（null 表示主 Agent）
     */
    record ToolStart(String toolName, String toolCallId, String arguments, SubAgentSource source) implements AgentStreamEvent {
        public ToolStart(String toolName, String toolCallId, String arguments) { this(toolName, toolCallId, arguments, null); }
    }

    /**
     * 工具执行完成。
     *
     * @param toolName  工具名称
     * @param toolCallId 工具调用 ID
     * @param result    工具返回结果
     * @param source    事件来源（null 表示主 Agent）
     */
    record ToolEnd(String toolName, String toolCallId, String result, SubAgentSource source) implements AgentStreamEvent {
        public ToolEnd(String toolName, String toolCallId, String result) { this(toolName, toolCallId, result, null); }
    }

    /**
     * 执行暂停事件，等待外部输入。
     *
     * <p>暂停原因（HITL 工具请求 / 用户主动中断）通过 {@link PauseState#getReason()} 区分，
     * 前端可按原因展示不同 UI（如 HITL 显示工具确认按钮、USER_INTERRUPT 显示"已停止"提示）。
     *
     * @param state  暂停状态（含 reason / interruptPhase 等扩展字段）
     * @param source 事件来源（null 表示主 Agent）
     */
    record Paused(PauseState state, SubAgentSource source) implements AgentStreamEvent {
        public Paused(PauseState state) { this(state, null); }
    }

    /**
     * LLM 调用异常事件（重试时发出）。
     *
     * @param code    错误码
     * @param message 用户友好提示
     * @param detail  异常详细信息（原始异常消息）
     * @param source  事件来源（null 表示主 Agent）
     */
    record Error(AgentErrorCode code, String message, String detail, SubAgentSource source) implements AgentStreamEvent {
        public Error(AgentErrorCode code, String message, String detail) { this(code, message, detail, null); }
    }

    /**
     * Agent 执行完成。
     *
     * @param totalPromptTokens     整个对话总输入 token 数
     * @param totalCompletionTokens 整个对话总输出 token 数
     * @param conversationId        会话 ID（主 Agent 才填，子 Agent 透传时丢弃；可为 null）
     * @param sessionId             本次执行对应的 agentx_session 主键 ID（主 Agent 才填；
     *                              可用于关联文件、外部资源等；为 null 表示未启用会话存储）
     * @param source                事件来源（null 表示主 Agent）
     */
    record Complete(long totalPromptTokens,
                    long totalCompletionTokens,
                    String conversationId,
                    Long sessionId,
                    SubAgentSource source) implements AgentStreamEvent {
        /** 兼容旧调用：仅 tokens + source */
        public Complete(long totalPromptTokens, long totalCompletionTokens, SubAgentSource source) {
            this(totalPromptTokens, totalCompletionTokens, null, null, source);
        }
        /** 兼容旧调用：仅 tokens */
        public Complete(long totalPromptTokens, long totalCompletionTokens) {
            this(totalPromptTokens, totalCompletionTokens, null, null, null);
        }
        /** 兼容旧调用：默认空 */
        public Complete() { this(0, 0, null, null, null); }
    }
}
