package com.agentx.ai.core.prompt;

/**
 * 默认系统提示词。
 *
 * 提供 ReactAgent 的默认行为指导，适用于通用任务场景。
 * 实际提示词内容统一维护在 {@link PromptConstants#DEFAULT_SYSTEM_PROMPT}。
 *
 * @author bigchui
 * 
 */
public final class DefaultSystemPrompt {

    private DefaultSystemPrompt() {
    }

    public static String get() {
        return PromptConstants.DEFAULT_SYSTEM_PROMPT;
    }
}
