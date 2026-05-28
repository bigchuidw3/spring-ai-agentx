package com.agentx.ai.core.agent.internal;

import com.agentx.ai.core.advisors.PauseAdvisor;
import com.agentx.ai.core.model.AgentStreamEvent;
import com.agentx.ai.core.model.PendingToolCall;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.stage.AgentExecutionContext;
import com.agentx.ai.core.stage.StageOutputManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具调用执行器 — 负责工具执行、结果收集、消息组装。
 *
 * <ul>
 *   <li>单个工具执行（含参数替换和错误处理）</li>
 *   <li>异步批量工具执行（保证顺序）</li>
 *   <li>暂停/恢复时的工具结果解析</li>
 *   <li>工具调用消息组装</li>
 * </ul>
 *
 * @author bigchui
 */
public class ToolCallExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolCallExecutor.class);

    private static final Set<String> APPROVAL_KEYWORDS = Set.of(
            "ok", "yes", "y", "好", "好的", "确认", "同意", "是", "是的", "approve", "confirm");

    private final Map<String, ToolCallback> toolMap;
    private final ObjectMapper objectMapper;
    private final PauseAdvisor pauseAdvisor;
    private final StageOutputManager stageManager;

    public ToolCallExecutor(Map<String, ToolCallback> toolMap, ObjectMapper objectMapper,
                            PauseAdvisor pauseAdvisor, StageOutputManager stageManager) {
        this.toolMap = toolMap;
        this.objectMapper = objectMapper;
        this.pauseAdvisor = pauseAdvisor;
        this.stageManager = stageManager;
    }

    /**
     * 执行单个工具调用。
     */
    public ToolExecutionResult executeSingleTool(AssistantMessage.ToolCall toolCall, RunnableParams params) {
        String toolName = toolCall.name();
        String argsJson = toolCall.arguments();

        // 无参数工具：LLM 可能返回 null 或空字符串，默认为空 JSON 对象
        if (argsJson == null || argsJson.isBlank()) {
            argsJson = "{}";
        }

        argsJson = replaceToolParams(argsJson, params);

        ToolCallback callback = toolMap.get(toolName);

        if (callback == null) {
            return errorResult(toolName, "不存在名为 '" + toolName + "' 的工具");
        }

        log.debug("Executing tool: {} with args: {}", toolName, argsJson);

        Object result;
        try {
            ToolContext toolContext = buildToolContext(params);
            result = callback.call(argsJson, toolContext);
        } catch (Exception e) {
            log.error("Tool '{}' execution failed: {}", toolName, e.getMessage(), e);
            String hint = buildErrorHint(toolName, e);
            return errorResult(toolName, hint);
        }

        String rawResult = result != null ? result.toString() : "{}";
        return new ToolExecutionResult(rawResult);
    }

    /**
     * 执行工具调用并收集阶段输出（非流式路径）。
     */
    public void executeToolCallsWithStage(List<AssistantMessage.ToolCall> toolCalls,
                                          List<Message> messages, RunnableParams params,
                                          AgentExecutionContext execCtx,
                                          Map<String, Object> stageOutputs) {
        for (AssistantMessage.ToolCall tc : toolCalls) {
            ToolExecutionResult result = executeSingleTool(tc, params);
            addToolCallMessages(tc, result, messages);

            execCtx.addToolRecord(tc.name(), tc.id(), tc.arguments(), result.rawResult());
            collectAfterToolEnd(execCtx, stageOutputs);
        }
    }

    /**
     * 执行非拦截的工具调用（拦截的由 resume 处理）。
     */
    public void executeNonPendingTools(List<AssistantMessage.ToolCall> allToolCalls,
                                       List<PendingToolCall> pending,
                                       List<Message> messages,
                                       RunnableParams params) {
        Set<String> pendingIds = new HashSet<>();
        for (PendingToolCall ptc : pending) {
            pendingIds.add(ptc.id());
        }

        for (AssistantMessage.ToolCall tc : allToolCalls) {
            if (!pendingIds.contains(tc.id())) {
                ToolExecutionResult result = executeSingleTool(tc, params);
                addToolCallMessages(tc, result, messages);
            }
        }
    }

    /**
     * 异步执行工具调用（保证顺序）。
     * 多个工具并发执行，但结果按原始 toolCalls 顺序添加到 messages。
     */
    public void executeToolCallsAsync(Sinks.Many<AgentStreamEvent> sink,
                                      List<AssistantMessage.ToolCall> toolCalls,
                                      List<Message> messages,
                                      RunnableParams params,
                                      AgentExecutionContext execCtx,
                                      Runnable onComplete) {
        int total = toolCalls.size();
        AtomicInteger completedCount = new AtomicInteger(0);
        List<List<Message>> results = new ArrayList<>(total);
        for (int i = 0; i < total; i++) results.add(null);
        List<ToolExecDetail> execDetails = new ArrayList<>(total);
        for (int i = 0; i < total; i++) execDetails.add(null);

        for (int i = 0; i < toolCalls.size(); i++) {
            final int index = i;
            AssistantMessage.ToolCall tc = toolCalls.get(i);

            Schedulers.boundedElastic().schedule(() -> {
                try {
                    ToolExecutionResult toolResult = executeSingleTool(tc, params);
                    results.set(index, collectToolCallMessages(tc, toolResult));
                    execDetails.set(index, new ToolExecDetail(tc, toolResult.rawResult(), null));
                } catch (Exception ex) {
                    log.error("Unexpected error in tool execution: {} - {}", tc.name(), ex.getMessage());
                    results.set(index, collectToolCallErrorMessages(tc, ex));
                    execDetails.set(index, new ToolExecDetail(tc, null, ex));
                } finally {
                    int completed = completedCount.incrementAndGet();
                    if (completed >= total) {
                        appendResultsInOrder(results, messages);

                        for (ToolExecDetail detail : execDetails) {
                            if (detail.error == null) {
                                sink.tryEmitNext(new AgentStreamEvent.ToolEnd(
                                        detail.toolCall.name(), detail.toolCall.id(), detail.rawResult));
                                if (execCtx != null) {
                                    execCtx.addToolRecord(detail.toolCall.name(), detail.toolCall.id(),
                                            detail.toolCall.arguments(), detail.rawResult);
                                    if (!stageManager.isEmpty()) {
                                        stageManager.afterToolEnd(execCtx.toStageContext(), sink::tryEmitNext);
                                    }
                                }
                            }
                        }

                        onComplete.run();
                    }
                }
            });
        }
    }

    /**
     * 解析恢复执行时工具调用的结果。
     */
    public String resolveResumeToolResult(PendingToolCall ptc,
                                          AssistantMessage.ToolCall toolCall,
                                          Map<String, String> toolResults,
                                          RunnableParams params) {
        // 用户输入工具：用户回答即工具结果，不需要执行
        if (pauseAdvisor != null && pauseAdvisor.isAskUserTool(ptc.name())) {
            return toolResults.getOrDefault(ptc.id(), "");
        }
        String userResponse = toolResults.getOrDefault(ptc.id(), "");
        // 用户拒绝：不执行工具
        if (!isApproved(userResponse)) {
            return ptc.name() + " 工具被用户拒绝执行：" + userResponse;
        }
        // 用户确认：实际执行
        if (toolMap.containsKey(ptc.name())) {
            ToolExecutionResult result = executeSingleTool(toolCall, params);
            return result.rawResult();
        }
        return userResponse;
    }

    // ==================== 消息组装 ====================

    public void addToolCallMessages(AssistantMessage.ToolCall toolCall,
                                    ToolExecutionResult result,
                                    List<Message> messages) {
        addNormalToolMessage(toolCall, result.rawResult(), messages);
    }

    public void addNormalToolMessage(AssistantMessage.ToolCall toolCall,
                                     String rawResult, List<Message> messages) {
        ToolResponseMessage.ToolResponse tr = new ToolResponseMessage.ToolResponse(
                toolCall.id(), toolCall.name(), rawResult);
        messages.add(ToolResponseMessage.builder()
                .responses(List.of(tr))
                .build());
    }

    public List<Message> collectToolCallMessages(AssistantMessage.ToolCall toolCall,
                                                 ToolExecutionResult result) {
        List<Message> collected = new ArrayList<>();
        addToolCallMessages(toolCall, result, collected);
        return collected;
    }

    public List<Message> collectToolCallErrorMessages(AssistantMessage.ToolCall toolCall,
                                                      Exception ex) {
        log.error("Unexpected error in tool execution: {} - {}", toolCall.name(), ex.getMessage());
        ToolExecutionResult errorResult = errorResult(toolCall.name(), "内部错误：" + ex.getMessage());
        List<Message> collected = new ArrayList<>();
        addNormalToolMessage(toolCall, errorResult.rawResult(), collected);
        return collected;
    }

    private void appendResultsInOrder(List<List<Message>> results, List<Message> messages) {
        for (List<Message> result : results) {
            messages.addAll(result);
        }
    }

    // ==================== 参数替换 ====================

    private String replaceToolParams(String argsJson, RunnableParams params) {
        if (params == null || params.getToolParams() == null || params.getToolParams().isEmpty()) {
            return argsJson;
        }
        if (argsJson == null || argsJson.isEmpty()) {
            return argsJson;
        }

        try {
            String result = argsJson;
            for (Map.Entry<String, Object> entry : params.getToolParams().entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                String regex = "(\"" + Pattern.quote(key) + "\"\\s*:\\s*)\"?default\"?";

                String replacement;
                if (value instanceof String) {
                    replacement = "$1\"" + Matcher.quoteReplacement((String) value) + "\"";
                } else {
                    replacement = "$1" + Matcher.quoteReplacement(String.valueOf(value));
                }

                String replaced = result.replaceAll(regex, replacement);
                if (!replaced.equals(result)) {
                    log.debug("替换工具参数: key={}, value={}", key, value);
                    result = replaced;
                }
            }

            return result;
        } catch (Exception e) {
            log.error("替换工具参数失败，使用原始参数: {}", argsJson, e);
            return argsJson;
        }
    }

    // ==================== 辅助方法 ====================

    private ToolContext buildToolContext(RunnableParams params) {
        Map<String, Object> context = new HashMap<>();
        if (params != null) {
            if (params.getUserId() != null) {
                context.put("userId", params.getUserId());
            }
            if (params.getConversationId() != null) {
                context.put("conversationId", params.getConversationId());
            }
        }
        return new ToolContext(context);
    }

    private String buildErrorHint(String toolName, Exception e) {
        if (e.getCause() instanceof JsonProcessingException
                || e instanceof JsonProcessingException) {
            return toolName + " 工具调用失败：参数 JSON 解析错误，可能是参数过长被截断。"
                    + "请尝试缩短参数或拆分为多步操作。";
        }
        return toolName + " 工具调用失败：" + e.getMessage();
    }

    private ToolExecutionResult errorResult(String toolName, String errorMessage) {
        try {
            Map<String, String> errorMap = new HashMap<>();
            errorMap.put("error", errorMessage);
            errorMap.put("tool", toolName);
            return new ToolExecutionResult(objectMapper.writeValueAsString(errorMap));
        } catch (JsonProcessingException ex) {
            return new ToolExecutionResult("{\"error\":\"" + errorMessage + "\"}");
        }
    }

    private boolean isApproved(String userResponse) {
        if (userResponse == null || userResponse.isBlank()) {
            return false;
        }
        return APPROVAL_KEYWORDS.contains(userResponse.trim().toLowerCase());
    }

    private void collectAfterToolEnd(AgentExecutionContext execCtx, Map<String, Object> stageOutputs) {
        if (stageManager.isEmpty()) {
            return;
        }
        stageManager.afterToolEnd(execCtx.toStageContext(), event -> {
            if (event instanceof AgentStreamEvent.StageOutput so && so.data() != null) {
                stageOutputs.put(so.stage(), so.data());
            }
        });
    }

    // ==================== 内部记录 ====================

    record ToolExecutionResult(String rawResult) {
    }

    record ToolExecDetail(AssistantMessage.ToolCall toolCall, String rawResult, Exception error) {
    }
}
