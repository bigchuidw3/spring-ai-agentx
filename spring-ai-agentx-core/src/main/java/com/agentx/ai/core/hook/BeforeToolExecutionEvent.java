package com.agentx.ai.core.hook;

import com.agentx.ai.core.stage.AgentRuntimeContext;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 单个工具执行前事件（callback.call 前）。
 *
 * <p>可修改：arguments 可替换（如沙箱路径重写）；toolContext 可追加条目。
 *
 * @author bigchui
 */
public final class BeforeToolExecutionEvent implements HookEvent {

    private final AgentRuntimeContext runtimeContext;
    private final String toolName;
    private final String toolCallId;
    private String arguments;
    private ToolContext toolContext;

    public BeforeToolExecutionEvent(AgentRuntimeContext runtimeContext, String toolName, String toolCallId,
                                     String arguments, ToolContext toolContext) {
        this.runtimeContext = runtimeContext;
        this.toolName = toolName;
        this.toolCallId = toolCallId;
        this.arguments = arguments;
        this.toolContext = toolContext;
    }

    public AgentRuntimeContext getRuntimeContext() {
        return runtimeContext;
    }

    public String getToolName() {
        return toolName;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getArguments() {
        return arguments;
    }

    public void setArguments(String arguments) {
        this.arguments = arguments;
    }

    public ToolContext getToolContext() {
        return toolContext;
    }

    public void setToolContext(ToolContext toolContext) {
        this.toolContext = toolContext;
    }
}
