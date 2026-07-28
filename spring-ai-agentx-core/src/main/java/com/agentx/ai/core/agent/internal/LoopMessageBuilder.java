package com.agentx.ai.core.agent.internal;

import com.agentx.ai.core.memory.store.SessionMessageStore;
import com.agentx.ai.core.memory.util.MemoryInjector;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.model.ThinkingMode;
import com.agentx.ai.core.prompt.PromptConstants;
import com.agentx.ai.core.tools.toolsearch.DeferredToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 消息构建器 — 构建初始消息列表并记录新消息边界。
 * 历史从 SessionMessageStore 加载完整消息链（含 ToolCall/ToolResponse）。
 *
 * @author bigchui
 */
public class LoopMessageBuilder {

    private static final Logger log = LoggerFactory.getLogger(LoopMessageBuilder.class);

    private final String instructions;
    private final SessionMessageStore sessionMessageStore;
    private final MemoryInjector memoryInjector;
    private final ThinkingMode thinkingMode;
    private final DeferredToolRegistry deferredToolRegistry;
    private final boolean todoWriteEnabled;
    private final boolean enableSession;

    public LoopMessageBuilder(String instructions, SessionMessageStore sessionMessageStore,
                              MemoryInjector memoryInjector, ThinkingMode thinkingMode,
                              DeferredToolRegistry deferredToolRegistry,
                              boolean todoWriteEnabled, boolean enableSession) {
        this.instructions = instructions;
        this.sessionMessageStore = sessionMessageStore;
        this.memoryInjector = memoryInjector;
        this.thinkingMode = thinkingMode;
        this.deferredToolRegistry = deferredToolRegistry;
        this.todoWriteEnabled = todoWriteEnabled;
        this.enableSession = enableSession;
    }

    /**
     * 构建初始消息列表，返回消息列表与新消息起点索引。
     * newMsgStartIndex 标记本次调用新增消息的边界，终态批量落库时据此切片。
     */
    public BuiltMessages buildInitialMessages(String query, RunnableParams params) {
        List<Message> messages = new ArrayList<>();

        // 1. 系统提示词
        String systemPrompt = "";
        if (instructions != null && !instructions.isBlank()) {
            systemPrompt = instructions;
        }
        systemPrompt = appendSection(systemPrompt, memoryInjector.buildMemorySection(params));

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

        // 2. 记忆上下文：合并为一条 AssistantMessage，作为背景参考放在短期历史上方
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
            messages.add(new AssistantMessage(memoryContext.toString().trim()));
        }

        // 3. 历史消息链（优先 working_messages 压缩视图，回退 original_messages）
        String conversationId = params != null ? params.getConversationId() : null;
        if (enableSession && sessionMessageStore != null && conversationId != null) {
            List<Message> history = sessionMessageStore.getMessages(conversationId, "working_messages");
            if (history.isEmpty()) {
                history = sessionMessageStore.getMessages(conversationId, "original_messages");
            }
            for (Message msg : history) {
                if (!(msg instanceof SystemMessage)) {
                    messages.add(msg);
                }
            }
        }

        // 4. 边界标记：此前的消息为历史/背景，此后为本轮新增
        int newMsgStartIndex = messages.size();

        // 5. 当前用户问题
        String userContent = query;
        if (params != null && params.getOutputType() != null) {
            BeanOutputConverter<?> converter = new BeanOutputConverter<>(
                    params.getOutputType().toTypeReference()
            );
            userContent = userContent + "\n" + converter.getFormat();
        }
        if (thinkingMode == ThinkingMode.DISABLED) {
            userContent = userContent + "\n<no_think>";
        }
        messages.add(new UserMessage(userContent));

        return new BuiltMessages(messages, newMsgStartIndex);
    }

    private String buildCustomParamSection(RunnableParams params) {
        if (params == null || params.getCustomParams() == null || params.getCustomParams().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## 系统参数（LLM 可见）\n");
        for (Map.Entry<String, Object> entry : params.getCustomParams().entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
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
