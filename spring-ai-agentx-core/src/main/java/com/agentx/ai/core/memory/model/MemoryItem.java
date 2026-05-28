package com.agentx.ai.core.memory.model;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 记忆条目 - 长期记忆的基本存储单元。
 *
 * 每条记忆包含：用户标识、语义类型、记忆正文、描述和扩展元数据。
 * 通过 Builder 模式构建，创建后不可变。
 *
 * @author bigchui
 * 
 */
public class MemoryItem {

    private final String id;
    private final String userId;
    private final MemoryType type;
    private final String content;
    private final String description;
    private final Map<String, Object> metadata;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    private MemoryItem(Builder builder) {
        this.id = builder.id != null ? builder.id : IdWorker.getIdStr();
        this.userId = builder.userId;
        this.type = builder.type;
        this.content = builder.content;
        this.description = builder.description;
        this.metadata = Collections.unmodifiableMap(
                builder.metadata != null ? builder.metadata : new HashMap<>());
        this.createdAt = builder.createdAt != null ? builder.createdAt : LocalDateTime.now();
        this.updatedAt = builder.updatedAt != null ? builder.updatedAt : this.createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 基于现有 MemoryItem 创建 Builder（用于更新场景）。
     */
    public Builder toBuilder() {
        return new Builder()
                .id(this.id)
                .userId(this.userId)
                .type(this.type)
                .content(this.content)
                .description(this.description)
                .metadata(new HashMap<>(this.metadata))
                .createdAt(this.createdAt)
                .updatedAt(LocalDateTime.now());
    }

    // ==================== Getters ====================

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public MemoryType getType() {
        return type;
    }

    public String getContent() {
        return content;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MemoryItem that = (MemoryItem) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "MemoryItem{" +
                "id='" + id + '\'' +
                ", userId='" + userId + '\'' +
                ", type=" + type +
                ", content='" + (content != null && content.length() > 50
                    ? content.substring(0, 50) + "..." : content) + '\'' +
                ", description='" + description + '\'' +
                '}';
    }

    /**
     * Builder 模式构建 MemoryItem。
     */
    public static class Builder {
        private String id;
        private String userId;
        private MemoryType type;
        private String content;
        private String description;
        private Map<String, Object> metadata;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder type(MemoryType type) {
            this.type = type;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder addMetadata(String key, Object value) {
            if (this.metadata == null) {
                this.metadata = new HashMap<>();
            }
            this.metadata.put(key, value);
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public MemoryItem build() {
            Objects.requireNonNull(userId, "userId must not be null");
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(content, "content must not be null");
            return new MemoryItem(this);
        }
    }
}
