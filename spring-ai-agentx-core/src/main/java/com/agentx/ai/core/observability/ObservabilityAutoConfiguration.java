package com.agentx.ai.core.observability;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 可观测性自动装配：LLM span 内容 Handler（input/output 原文）内置注册。
 *
 * <p>调用方只需引入 OTel bridge + exporter 依赖、配置 endpoint 并在
 * ReactAgent 上传 {@code .observationRegistry(...)} 与 {@code .tracer(...)}，
 * 无需再手动 {@code observationRegistry.observationConfig().observationHandler(...)}。
 *
 * @author bigchui
 */
@AutoConfiguration
@ConditionalOnClass(ObservationRegistry.class)
public class ObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ChatContentObservationHandler chatContentObservationHandler() {
        return new ChatContentObservationHandler();
    }
}
