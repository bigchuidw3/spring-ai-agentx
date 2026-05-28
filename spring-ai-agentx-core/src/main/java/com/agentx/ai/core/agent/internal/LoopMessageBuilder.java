package com.agentx.ai.core.agent.internal;

import com.agentx.ai.core.memory.store.AgentChatMemory;
import com.agentx.ai.core.memory.util.MemoryInjector;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.model.ThinkingMode;
import com.agentx.ai.core.prompt.PromptConstants;
import com.agentx.ai.core.tools.toolsearch.DeferredToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 消息构建器 — 负责初始消息列表构建和会话历史持久化。
 *
 * <ul>
 *   <li>构建系统提示词（instructions + 记忆 + 自定义参数 + 工具发现引导）</li>
 *   <li>加载会话历史</li>
 *   <li>结构化输出格式注入</li>
 *   <li>会话历史保存到 ChatMemory</li>
 * </ul>
 *
 * @author bigchui
 */
public class LoopMessageBuilder {

    private static final Logger log = LoggerFactory.getLogger(LoopMessageBuilder.class);

    private final String instructions;
    private final ChatMemory chatMemory;
    private final MemoryInjector memoryInjector;
    private final ThinkingMode thinkingMode;
    private final DeferredToolRegistry deferredToolRegistry;

    public LoopMessageBuilder(String instructions, ChatMemory chatMemory,
                              MemoryInjector memoryInjector, ThinkingMode thinkingMode,
                              DeferredToolRegistry deferredToolRegistry) {
        this.instructions = instructions;
        this.chatMemory = chatMemory;
        this.memoryInjector = memoryInjector;
        this.thinkingMode = thinkingMode;
        this.deferredToolRegistry = deferredToolRegistry;
    }

    /**
     * 构建初始消息列表。
     */
    public List<Message> buildInitialMessages(String query, RunnableParams params) {
        List<Message> messages = new ArrayList<>();

        // 构建系统提示词
        String systemPrompt = "";
        if (instructions != null && !instructions.isBlank()) {
            systemPrompt = instructions;
        }

        systemPrompt = appendSection(systemPrompt, memoryInjector.buildMemorySection(params));
        systemPrompt = appendSection(systemPrompt, memoryInjector.buildSemanticSection(params, query));

        // 注入自定义参数
        String customParamSection = buildCustomParamSection(params);
        if (!customParamSection.isEmpty()) {
            systemPrompt = systemPrompt + customParamSection;
        }

        if (deferredToolRegistry != null) {
            systemPrompt = appendSection(systemPrompt, PromptConstants.TOOL_SEARCH_GUIDANCE);
        }

        if (!systemPrompt.isEmpty()) {
            messages.add(new SystemMessage(systemPrompt));
        }

        String conversationId = params != null ? params.getConversationId() : null;
        if (chatMemory != null && conversationId != null) {
            List<Message> history = chatMemory.get(conversationId);
            for (Message msg : history) {
                if (!(msg instanceof SystemMessage)) {
                    messages.add(msg);
                }
            }
        }

        // 结构化输出格式指令追加到 UserMessage
        String userContent = query;
        if (params != null && params.getOutputType() != null) {
            BeanOutputConverter<?> converter = new BeanOutputConverter<>(
                    params.getOutputType().toTypeReference()
            );
            userContent = userContent + "\n" + converter.getFormat();
        }

        // thinkingMode=DISABLED 时追加 <no_think> 标签
        if (thinkingMode == ThinkingMode.DISABLED) {
            userContent = userContent + "\n<no_think>";
        }

        messages.add(new UserMessage(userContent));

        return messages;
    }

    /**
     * 保存会话历史到 ChatMemory。
     */
    public void saveToChatMemory(String query, String answer, String think,
                                 String conversationId, String userId) {
        if (chatMemory == null || conversationId == null || query == null || query.isEmpty()) {
            return;
        }
        try {
            if (chatMemory instanceof AgentChatMemory acm) {
                acm.add(conversationId, userId, query, answer, think);
            } else {
                chatMemory.add(conversationId, List.of(
                        new UserMessage(query),
                        new AssistantMessage(answer)
                ));
            }
            log.debug("Saved conversation to agentx_session: conversationId={}, userId={}", conversationId, userId);
        } catch (Exception e) {
            log.error("Failed to save conversation to agentx_session: {}", e.getMessage());
        }
    }

    private String buildCustomParamSection(RunnableParams params) {
        if (params == null || params.getCustomParams() == null || params.getCustomParams().isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## 系统参数（可用于工具调用）\n");
        for (Map.Entry<String, Object> entry : params.getCustomParams().entrySet()) {
            String key = entry.getKey();
            if (params.getToolParams() != null && params.getToolParams().containsKey(key)) {
                sb.append(key).append(": default\n");
            } else {
                sb.append(key).append(": ").append(entry.getValue()).append("\n");
            }
        }
        return sb.toString();
    }

    private static String appendSection(String base, String section) {
        if (section == null || section.isEmpty()) {
            return base;
        }
        return base.isEmpty() ? section : base + "\n\n" + section;
    }
}
