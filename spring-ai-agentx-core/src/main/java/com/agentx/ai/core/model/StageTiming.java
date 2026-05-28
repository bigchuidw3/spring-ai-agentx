package com.agentx.ai.core.model;

/**
 * 阶段输出插入时机。
 *
 * 定义自定义 {@link StageOutputProvider} 可以挂载的生命周期钩子点。
 *
 * <pre>
 * AgentStart
 *   │
 *   ▼  ← AFTER_START
 * ┌─── Loop ─────────────────────┐
 * │  Thinking / Text              │
 * │  ToolStart                    │
 * │  ToolEnd                      │
 * │   ▼  ← AFTER_TOOL_END        │
 * │   └──→ 继续下一轮             │
 * └───────────────────────────────┘
 *   │
 *   ▼
 * Complete
 *   ▲  ← BEFORE_COMPLETE
 * </pre>
 *
 * @author bigchui
 * 
 */
public enum StageTiming {

    /**
     * AgentStart 之后，第一轮 LLM 调用之前。
     * 适用场景：欢迎语、初始化提示。
     */
    AFTER_START,

    /**
     * 每次工具执行完成后。
     * 适用场景：实时展示工具结果（如搜索卡片）。
     */
    AFTER_TOOL_END,

    /**
     * 最终答案之后，Complete 之前。
     * 适用场景：引用链接、推荐问题、摘要。
     */
    BEFORE_COMPLETE
}
