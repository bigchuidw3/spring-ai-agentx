package com.agentx.ai.core.sandbox.docker;

/**
 * 容器命名工具。
 *
 * <p>把 scope key（conversationId / userId）清洗为合法的 Docker 容器名。
 * Docker 容器名只能包含 {@code [a-zA-Z0-9_.-]}，且不能以 {@code . _ -} 开头。
 *
 * @author bigchui
 */
public final class ContainerNameUtil {

    /**
     * AgentX 沙箱容器统一前缀
     */
    public static final String PREFIX = "agentx-sandbox-";

    /**
     * scope key 最大长度（容器名限制 200 字符，减去前缀 16 字符和余量）
     */
    private static final int MAX_SCOPE_LENGTH = 180;

    private ContainerNameUtil() {
    }

    /**
     * 计算 scope key 对应的容器名。
     *
     * @param scopeKey 会话级或用户级标识（conversationId / userId）
     * @return 容器名，如 {@code agentx-sandbox-conv_001}
     */
    public static String toContainerName(String scopeKey) {
        if (scopeKey == null || scopeKey.isBlank()) {
            throw new IllegalArgumentException("scopeKey must not be null or blank");
        }
        String sanitized = scopeKey.replaceAll("[^a-zA-Z0-9_.-]", "_");
        if (sanitized.length() > MAX_SCOPE_LENGTH) {
            sanitized = sanitized.substring(0, MAX_SCOPE_LENGTH);
        }
        return PREFIX + sanitized;
    }
}
