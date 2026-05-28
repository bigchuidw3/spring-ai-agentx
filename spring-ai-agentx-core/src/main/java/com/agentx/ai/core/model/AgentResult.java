package com.agentx.ai.core.model;

import com.agentx.ai.core.exception.AgentErrorCode;
import com.agentx.ai.core.exception.AgentException;

import java.util.Collections;
import java.util.Map;

/**
 * Agent 执行结果。
 *
 * 可扩展的 sealed 接口，支持三种结果：
 * <ul>
 *   <li>{@link Completed} - 执行完成，包含最终答案和阶段输出</li>
 *   <li>{@link Paused} - 执行暂停，等待外部输入（Human-in-the-Loop）</li>
 *   <li>{@link Failed} - 执行失败（如 LLM 调用异常、重试耗尽等）</li>
 * </ul>
 *
 * @author bigchui
 * 
 */
public sealed interface AgentResult permits AgentResult.Completed, AgentResult.Paused, AgentResult.Failed {

    /**
     * 执行完成。
     *
     * @param answer       最终答案文本（不含思考内容）
     * @param think        思考内容（reasoning_content 或 &lt;think/&gt; 标签内内容），无思考时为 null
     * @param stageOutputs 阶段输出（key=阶段名称, value=输出数据），无阶段输出时为空 Map
     */
    record Completed(String answer, String think, Map<String, Object> stageOutputs) implements AgentResult {

        /**
         * 便捷构造：无思考、无阶段输出。
         *
         * @param answer 最终答案文本
         */
        public Completed(String answer) {
            this(answer, null, Collections.emptyMap());
        }

        /**
         * 便捷构造：无阶段输出。
         *
         * @param answer 最终答案文本
         * @param think  思考内容
         */
        public Completed(String answer, String think) {
            this(answer, think, Collections.emptyMap());
        }
    }

    /**
     * 执行暂停，等待外部输入。
     *
     * @param state 暂停状态，包含恢复所需的所有信息
     */
    record Paused(PauseState state) implements AgentResult {
    }

    /**
     * 执行失败（LLM 调用异常、重试耗尽等）。
     *
     * @param error 错误信息
     * @param code  错误码
     */
    record Failed(String error, AgentErrorCode code) implements AgentResult {
    }

    /**
     * 是否处于暂停状态。
     */
    default boolean isPaused() {
        return this instanceof Paused;
    }

    /**
     * 是否执行失败。
     */
    default boolean isFailed() {
        return this instanceof Failed;
    }

    /**
     * 获取思考内容。
     *
     * <p>仅在 {@link Completed} 时返回思考内容，可能为 null（模型未输出思考）。
     * 处于 {@link Paused} 或 {@link Failed} 状态时抛出异常。
     *
     * @return 思考内容，无思考时返回 null
     * @throws IllegalStateException 如果处于暂停或失败状态
     */
    default String think() {
        if (this instanceof Completed c) {
            return c.think();
        }
        if (this instanceof Paused) {
            throw new IllegalStateException("Agent 暂停中，请调用 resume() 继续对话");
        }
        if (this instanceof Failed f) {
            throw new AgentException(f.code(), f.error());
        }
        throw new IllegalStateException("未知结果类型: " + this);
    }

    /**
     * 获取最终答案。
     *
     * <p>仅在 {@link Completed} 时返回答案文本。
     * 处于 {@link Paused} 或 {@link Failed} 状态时抛出异常。
     *
     * @return 最终答案文本
     * @throws IllegalStateException 如果处于暂停或失败状态
     */
    default String answer() {
        if (this instanceof Completed c) {
            return c.answer();
        }
        if (this instanceof Paused) {
            throw new IllegalStateException("Agent 暂停中，请调用 resume() 继续对话");
        }
        if (this instanceof Failed f) {
            throw new AgentException(f.code(), f.error());
        }
        throw new IllegalStateException("未知结果类型: " + this);
    }
}
