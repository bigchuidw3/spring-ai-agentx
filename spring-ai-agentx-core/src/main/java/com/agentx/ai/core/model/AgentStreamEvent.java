package com.agentx.ai.core.model;

import com.agentx.ai.core.exception.AgentErrorCode;
import com.agentx.ai.core.tools.TodoWriteTool.TodoItem;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

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
 *   <li>{@link AgentStart} - Agent 开始执行</li>
 *   <li>{@link Thinking} - LLM 思考过程（think 标签内）</li>
 *   <li>{@link Text} - LLM 正常文本输出</li>
 *   <li>{@link ToolStart} - 工具即将执行</li>
 *   <li>{@link ToolEnd} - 工具执行完成</li>
 *   <li>{@link TodoProgress} - 任务列表进度更新（TodoWrite 工具触发）</li>
 *   <li>{@link Paused} - 执行暂停，等待外部输入</li>
 *   <li>{@link StageOutput} - 自定义阶段输出（由 {@link StageOutputProvider} 产生）</li>
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
    @JsonSubTypes.Type(value = AgentStreamEvent.AgentStart.class, name = "AgentStart"),
    @JsonSubTypes.Type(value = AgentStreamEvent.Thinking.class, name = "Thinking"),
    @JsonSubTypes.Type(value = AgentStreamEvent.Text.class, name = "Text"),
    @JsonSubTypes.Type(value = AgentStreamEvent.ToolStart.class, name = "ToolStart"),
    @JsonSubTypes.Type(value = AgentStreamEvent.ToolEnd.class, name = "ToolEnd"),
    @JsonSubTypes.Type(value = AgentStreamEvent.TodoProgress.class, name = "TodoProgress"),
    @JsonSubTypes.Type(value = AgentStreamEvent.Paused.class, name = "Paused"),
    @JsonSubTypes.Type(value = AgentStreamEvent.StageOutput.class, name = "StageOutput"),
    @JsonSubTypes.Type(value = AgentStreamEvent.Error.class, name = "Error"),
    @JsonSubTypes.Type(value = AgentStreamEvent.Complete.class, name = "Complete")
})
public sealed interface AgentStreamEvent permits
        AgentStreamEvent.AgentStart,
        AgentStreamEvent.Thinking,
        AgentStreamEvent.Text,
        AgentStreamEvent.ToolStart,
        AgentStreamEvent.ToolEnd,
        AgentStreamEvent.TodoProgress,
        AgentStreamEvent.Paused,
        AgentStreamEvent.StageOutput,
        AgentStreamEvent.Error,
        AgentStreamEvent.Complete {

    /**
     * Agent 开始执行。
     */
    record AgentStart(SubAgentSource source) implements AgentStreamEvent {
        public AgentStart() { this(null); }
    }

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
     * 任务列表进度更新（TodoWrite 工具触发）。
     * <p>
     * 每次 LLM 调用 TodoWrite 更新任务列表时，框架自动发射此事件。
     * 前端可据此实时渲染任务进度面板。
     *
     * @param items  当前所有任务项
     * @param source 事件来源（null 表示主 Agent）
     */
    record TodoProgress(List<TodoItem> items, SubAgentSource source) implements AgentStreamEvent {
        public TodoProgress(List<TodoItem> items) { this(items, null); }
    }

    /**
     * 执行暂停事件，等待外部输入。
     *
     * @param state  暂停状态
     * @param source 事件来源（null 表示主 Agent）
     */
    record Paused(PauseState state, SubAgentSource source) implements AgentStreamEvent {
        public Paused(PauseState state) { this(state, null); }
    }

    /**
     * 自定义阶段输出（由 {@link StageOutputProvider} 产生）。
     *
     * @param stage  阶段名称（如 "reference"、"recommend"）
     * @param data   阶段输出数据
     * @param source 事件来源（null 表示主 Agent）
     */
    record StageOutput(String stage, Object data, SubAgentSource source) implements AgentStreamEvent {
        public StageOutput(String stage, Object data) { this(stage, data, null); }
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
