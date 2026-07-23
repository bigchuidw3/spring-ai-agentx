package com.agentx.ai.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Agent 调用参数。
 *
 * 用于传递执行时的额外参数，支持会话管理、长期记忆、上下文注入和工具参数替换。
 *
 * conversationId 用于 ChatMemory 会话管理和流式停止。
 * userId 用于长期记忆（MemoryStore）的用户维度标识，跨会话持久。
 *
 * customParams 和 toolParams 分别面向不同对象：
 * - addParam("language", "zh-CN")：将参数注入系统提示词，LLM 可见并可直接使用。
 * - addToolParam("userId", "123")：不注入系统提示词，只在工具执行前按 inputSchema 注入真实参数。
 *
 * toolParams 适合传递 userId、token、租户 ID 等不应依赖 LLM 生成的运行时参数。
 *
 * @author bigchui
 * 
 */
public class RunnableParams {

    private final String conversationId;
    private final String userId;
    private final Map<String, Object> customParams;
    private final Map<String, Object> toolParams;
    private final OutputType outputType;

    private RunnableParams(Builder builder) {
        this.conversationId = builder.conversationId;
        this.userId = builder.userId;
        this.customParams = Collections.unmodifiableMap(builder.customParams);
        this.toolParams = Collections.unmodifiableMap(builder.toolParams);
        this.outputType = builder.outputType;
    }

    @JsonCreator
    private RunnableParams(
            @JsonProperty("conversationId") String conversationId,
            @JsonProperty("userId") String userId,
            @JsonProperty("customParams") Map<String, Object> customParams,
            @JsonProperty("toolParams") Map<String, Object> toolParams,
            @JsonProperty("outputType") OutputType outputType) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.customParams = customParams != null
                ? Collections.unmodifiableMap(new HashMap<>(customParams)) : Collections.emptyMap();
        this.toolParams = toolParams != null
                ? Collections.unmodifiableMap(new HashMap<>(toolParams)) : Collections.emptyMap();
        this.outputType = outputType;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static RunnableParams empty() {
        return new Builder().build();
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getUserId() {
        return userId;
    }

    public Map<String, Object> getCustomParams() {
        return customParams;
    }

    public Map<String, Object> getToolParams() {
        return toolParams;
    }

    public OutputType getOutputType() {
        return outputType;
    }

    @SuppressWarnings("unchecked")
    public <T> T getParam(String key) {
        return (T) customParams.get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T getParam(String key, T defaultValue) {
        Object value = customParams.get(key);
        return value != null ? (T) value : defaultValue;
    }

    public boolean hasParam(String key) {
        return customParams.containsKey(key);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RunnableParams that = (RunnableParams) o;
        return Objects.equals(conversationId, that.conversationId) &&
            Objects.equals(userId, that.userId) &&
            Objects.equals(customParams, that.customParams) &&
            Objects.equals(toolParams, that.toolParams) &&
            Objects.equals(outputType, that.outputType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(conversationId, userId, customParams, toolParams, outputType);
    }

    @Override
    public String toString() {
        return "RunnableParams{" +
            "conversationId='" + conversationId + '\'' +
            ", userId='" + userId + '\'' +
            ", customParams=" + customParams +
            ", toolParams=" + toolParams +
            ", outputType=" + outputType +
            '}';
    }

    public static class Builder {
        private String conversationId;
        private String userId;
        private final Map<String, Object> customParams = new HashMap<>();
        private final Map<String, Object> toolParams = new HashMap<>();
        private OutputType outputType;

        public Builder conversationId(String conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder addParam(String key, Object value) {
            this.customParams.put(key, value);
            return this;
        }

        public Builder addParams(Map<String, Object> params) {
            if (params != null) {
                this.customParams.putAll(params);
            }
            return this;
        }

        public Builder addToolParam(String key, Object value) {
            this.toolParams.put(key, value);
            return this;
        }

        public Builder addToolParams(Map<String, Object> params) {
            if (params != null) {
                this.toolParams.putAll(params);
            }
            return this;
        }

        public Builder outputType(OutputType outputType) {
            this.outputType = outputType;
            return this;
        }

        public Builder outputType(Class<?> clazz) {
            this.outputType = OutputType.of(clazz);
            return this;
        }

        public RunnableParams build() {
            return new RunnableParams(this);
        }
    }
}
