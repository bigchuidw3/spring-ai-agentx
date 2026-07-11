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
    private final boolean todoWriteEnabled;
    private final boolean enableSession;
    private final int maxHistoryRounds;
    private final int maxHistoryTokens;

    public LoopMessageBuilder(String instructions, ChatMemory chatMemory,
                              MemoryInjector memoryInjector, ThinkingMode thinkingMode,
                              DeferredToolRegistry deferredToolRegistry,
                              boolean todoWriteEnabled, boolean enableSession,
                              int maxHistoryRounds, int maxHistoryTokens) {
        this.instructions = instructions;
        this.chatMemory = chatMemory;
        this.memoryInjector = memoryInjector;
        this.thinkingMode = thinkingMode;
        this.deferredToolRegistry = deferredToolRegistry;
        this.todoWriteEnabled = todoWriteEnabled;
        this.enableSession = enableSession;
        this.maxHistoryRounds = maxHistoryRounds;
        this.maxHistoryTokens = maxHistoryTokens;
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

        // 注入自定义参数
        String customParamSection = buildCustomParamSection(params);
        if (!customParamSection.isEmpty()) {
            systemPrompt = systemPrompt + customParamSection;
        }

        if (deferredToolRegistry != null) {
            systemPrompt = appendSection(systemPrompt, PromptConstants.TOOL_SEARCH_GUIDANCE);
        }

        if (todoWriteEnabled) {
            systemPrompt = appendSection(systemPrompt, PromptConstants.TODO_WRITE_GUIDANCE);
        }

        if (!systemPrompt.isEmpty()) {
            messages.add(new SystemMessage(systemPrompt));
        }

        // 记忆区块：合并为一条 UserMessage，作为背景参考放在短期历史上方
        String crossSection = memoryInjector.buildCrossSummarySection(params, query);
        String sessionSection = memoryInjector.buildSessionSummarySection(params);
        if (!sessionSection.isEmpty() || !crossSection.isEmpty()) {
            StringBuilder memoryContext = new StringBuilder();
            memoryContext.append("以下是本次对话的相关背景信息，供参考：\n\n");
            if (!sessionSection.isEmpty()) {
                memoryContext.append(sessionSection).append("\n\n");
            }
            if (!crossSection.isEmpty()) {
                memoryContext.append(crossSection);
            }
            messages.add(new UserMessage(memoryContext.toString().trim()));
        }

        String conversationId = params != null ? params.getConversationId() : null;
        if (enableSession && chatMemory != null && conversationId != null) {
            List<Message> history;
            if (chatMemory instanceof AgentChatMemory acm) {
                history = acm.get(conversationId, maxHistoryRounds, maxHistoryTokens);
            } else {
                history = chatMemory.get(conversationId);
            }
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
     * 保存会话启动记录到 ChatMemory（仅 question，answer 为空）。
     * <p>
     * 在 Agent 执行开始时调用，确保中断/异常场景下前端仍有历史记录可展示。
     * 后续完成时通过 UPSERT 更新 answer / think / timeline。
     *
     * @param query          用户问题
     * @param conversationId 会话 ID
     * @param userId         用户标识（可为 null）
     * @param sessionId      预生成的 agentx_session 主键 ID
     */
    public void saveSessionStart(String query, String conversationId, String userId, long sessionId) {
        if (!enableSession || chatMemory == null || conversationId == null
                || query == null || query.isEmpty() || sessionId == 0L) {
            return;
        }
        try {
            if (chatMemory instanceof AgentChatMemory acm) {
                acm.upsert(sessionId, conversationId, userId, query, null, null, null);
            }
            log.debug("Saved session start: conversationId={}, sessionId={}", conversationId, sessionId);
        } catch (Exception e) {
            log.error("Failed to save session start: {}", e.getMessage());
        }
    }

    /**
     * 保存会话历史到 ChatMemory。
     *
     * @param query          用户问题
     * @param answer         助手回答（正文）
     * @param think          思考过程（可为 null）
     * @param timelineJson   时间线 JSON（可为 null）
     * @param conversationId 会话 ID
     * @param userId         用户标识（可为 null）
     */
    public void saveToChatMemory(String query, String answer, String think, String timelineJson,
                                 String conversationId, String userId) {
        saveToChatMemory(query, answer, think, timelineJson, conversationId, userId, 0L);
    }

    /**
     * 保存会话历史到 ChatMemory，并显式传入 sessionId。
     * <p>
     * sessionId 用于让 agentx_session 主键与外部上下文（trace、文件关联等）对齐。
     * 非 {@link AgentChatMemory} 实现的 ChatMemory 会忽略 sessionId。
     *
     * @param query          用户问题
     * @param answer         助手回答（正文）
     * @param think          思考过程（可为 null）
     * @param timelineJson   时间线 JSON（可为 null）
     * @param conversationId 会话 ID
     * @param userId         用户标识（可为 null）
     * @param sessionId      预生成的 agentx_session 主键 ID；为 0 时由底层自动生成
     */
    public void saveToChatMemory(String query, String answer, String think, String timelineJson,
                                 String conversationId, String userId, long sessionId) {
        if (!enableSession || chatMemory == null || conversationId == null || query == null || query.isEmpty()) {
            return;
        }
        try {
            if (chatMemory instanceof AgentChatMemory acm) {
                acm.upsert(sessionId, conversationId, userId, query, answer, think, timelineJson);
            } else {
                chatMemory.add(conversationId, List.of(
                        new UserMessage(query),
                        new AssistantMessage(answer)
                ));
            }
            log.debug("Saved conversation to agentx_session: conversationId={}, userId={}, sessionId={}",
                    conversationId, userId, sessionId);
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
