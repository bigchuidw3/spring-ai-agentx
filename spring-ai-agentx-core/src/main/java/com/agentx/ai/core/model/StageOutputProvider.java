package com.agentx.ai.core.model;

/**
 * 阶段输出提供者 SPI。
 *
 * 调用方实现此接口，注册自定义后处理阶段（如 reference、recommend），
 * 框架在 {@link #timing()} 声明的生命周期钩子点自动调用。
 *
 * <p>使用示例：
 * <pre>{@code
 * public class ReferenceProvider implements StageOutputProvider {
 *     public String name() { return "reference"; }
 *     public StageTiming timing() { return StageTiming.BEFORE_COMPLETE; }
 *
 *     public Object produce(StageContext ctx) {
 *         List<ToolRecord> searchResults = ctx.toolRecords().stream()
 *             .filter(r -> "web_search".equals(r.toolName()))
 *             .toList();
 *         if (searchResults.isEmpty()) return null;
 *         return parseReferences(searchResults);
 *     }
 * }
 * }</pre>
 *
 * @author bigchui
 * 
 */
public interface StageOutputProvider {

    /**
     * 阶段名称。
     * 用于标识 {@link AgentStreamEvent.StageOutput} 事件的来源。
     *
     * @return 阶段名称（如 "reference"、"recommend"）
     */
    String name();

    /**
     * 插入时机。
     * 声明此 Provider 在哪个生命周期钩子点被调用。
     *
     * @return 插入时机
     */
    StageTiming timing();

    /**
     * 产生阶段输出。
     *
     * <p>框架在 {@link #timing()} 声明的钩子点调用此方法。
     * 返回 null 表示本阶段无输出（不会发出 StageOutput 事件）。
     *
     * @param context 阶段上下文，包含查询、答案、工具记录等
     * @return 阶段输出数据，或 null 表示无输出
     */
    Object produce(StageContext context);
}
