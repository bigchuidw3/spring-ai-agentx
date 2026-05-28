package com.agentx.ai.core.stage;

import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.model.StageContext;
import com.agentx.ai.core.model.ToolRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 执行期上下文。
 *
 * 在一次 {@code stream()} 或 {@code call()} 调用期间存活，
 * 累积收集工具执行记录、最终答案等数据。
 * 在生命周期钩子点通过 {@link #toStageContext()} 生成不可变快照传给 {@link StageOutputProvider}。
 *
 * @author bigchui
 * 
 */
public class AgentExecutionContext {

    private final String query;
    private final RunnableParams params;
    private final List<ToolRecord> toolRecords = new ArrayList<>();
    private String answer;

    public AgentExecutionContext(String query, RunnableParams params) {
        this.query = query;
        this.params = params;
    }

    /**
     * 添加一条工具执行记录。
     */
    public void addToolRecord(String toolName, String toolCallId, String arguments, String result) {
        this.toolRecords.add(new ToolRecord(toolName, toolCallId, arguments, result));
    }

    /**
     * 设置最终答案。
     */
    public void setAnswer(String answer) {
        this.answer = answer;
    }

    /**
     * 获取累积的工具执行记录。
     */
    public List<ToolRecord> getToolRecords() {
        return toolRecords;
    }

    /**
     * 转成不可变快照，传给 StageOutputProvider。
     */
    public StageContext toStageContext() {
        return new StageContext(query, answer, params, List.copyOf(toolRecords));
    }
}
