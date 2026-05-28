package com.agentx.ai.core.model;

import java.util.List;

/**
 * 阶段输出上下文。
 *
 * 传递给 {@link StageOutputProvider#produce(StageContext)} 的不可变快照，
 * 包含当前 Agent 执行的所有上下文信息。
 *
 * @param query       原始用户问题
 * @param answer      最终答案（仅在 {@link StageTiming#BEFORE_COMPLETE} 时有值）
 * @param params      调用参数
 * @param toolRecords 本轮所有工具调用记录（按执行顺序）
 * @author bigchui
 * 
 */
public record StageContext(
        String query,
        String answer,
        RunnableParams params,
        List<ToolRecord> toolRecords
) {

    private static final StageContext EMPTY = new StageContext(null, null, null, List.of());

    /**
     * 获取最后一个工具执行记录。
     *
     * @return 最后一个 ToolRecord，无记录时返回 null
     */
    public ToolRecord lastToolRecord() {
        if (toolRecords == null || toolRecords.isEmpty()) {
            return null;
        }
        return toolRecords.get(toolRecords.size() - 1);
    }

    /**
     * 空上下文。
     */
    public static StageContext empty() {
        return EMPTY;
    }
}
