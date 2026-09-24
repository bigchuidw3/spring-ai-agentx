package com.agentx.ai.core.observability;

import io.micrometer.observation.ObservationPredicate;

/**
 * 观测范围过滤策略 — 供宿主组装 {@link ObservationPredicate}，屏蔽 Spring AI
 * 内部观测命名等实现细节。
 *
 * <p>过滤规则：
 * <ul>
 * <li>LLM span（Spring AI 的 spring_ai / gen_ai 前缀观测，含模型名/token/耗时）始终放行
 *     ——它们是 Agent 调用链的组成部分</li>
 * <li>其余全部拦截（HTTP 入口、定时任务、Security、JDBC、Advisor 中间层等框架噪声）</li>
 * </ul>
 * 效果：观测平台中只有 Agent 调用链（trace 根为 agentx 前缀命名），无其他干扰。
 *
 * <p>用法（宿主配置类）：
 * <pre>{@code
 * @Bean
 * public ObservationPredicate observationPredicate() {
 *     return ObservabilityPredicates.agentOnly();
 * }
 * }</pre>
 *
 * <p>注意：agentx.* span 由 {@link TracingHook} 直接经 Tracer 产生，
 * 不经过 ObservationPredicate，天然存在。
 *
 * @author bigchui
 */
public final class ObservabilityPredicates {

    private ObservabilityPredicates() {
    }

    /**
     * 只保留 Agent/LLM 链路：LLM span 放行，其余观测全部拦截。
     */
    public static ObservationPredicate agentOnly() {
        return (name, context) -> name.startsWith("spring_ai") || name.startsWith("gen_ai");
    }
}
