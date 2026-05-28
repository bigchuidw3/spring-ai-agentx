package com.agentx.ai.core.agent.internal;

import com.agentx.ai.core.exception.AgentErrorCode;
import com.agentx.ai.core.exception.AgentException;
import com.agentx.ai.core.model.AgentStreamEvent;
import com.agentx.ai.core.tools.toolsearch.DeferredToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * LLM 调用器 — 负责同步 LLM 调用（含重试）和 ChatClient 每轮构建。
 *
 * <ul>
 *   <li>同步 LLM 调用 + 自动重试</li>
 *   <li>DeferredToolRegistry 场景下的 ChatClient 每轮重建</li>
 *   <li>HTTP 响应体提取（用于错误日志）</li>
 * </ul>
 *
 * @author bigchui
 */
public class LlmInvoker {

    private static final Logger log = LoggerFactory.getLogger(LlmInvoker.class);

    static final long RETRY_INTERVAL_MS = 10000;

    private final ChatClient chatClient;
    private final ChatModel chatModel;
    private final int maxRetries;
    private final List<Advisor> advisors;
    private final List<ToolCallback> alwaysLoadTools;
    private final DeferredToolRegistry deferredToolRegistry;
    private final DeferredToolRegistry.Session deferredToolSession;

    public LlmInvoker(ChatClient chatClient, ChatModel chatModel,
                      int maxRetries, List<Advisor> advisors,
                      List<ToolCallback> alwaysLoadTools,
                      DeferredToolRegistry deferredToolRegistry,
                      DeferredToolRegistry.Session deferredToolSession) {
        this.chatClient = chatClient;
        this.chatModel = chatModel;
        this.maxRetries = maxRetries;
        this.advisors = advisors;
        this.alwaysLoadTools = alwaysLoadTools;
        this.deferredToolRegistry = deferredToolRegistry;
        this.deferredToolSession = deferredToolSession;
    }

    /**
     * 同步调用 LLM（含重试）。
     */
    public ChatClientResponse callLlm(List<Message> messages) {
        Throwable lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                ChatClientResponse resp = buildRoundChatClient().prompt()
                        .messages(messages)
                        .call()
                        .chatClientResponse();
                if (resp.chatResponse() == null || resp.chatResponse().getResult() == null) {
                    throw new AgentException(AgentErrorCode.LLM_EMPTY_RESPONSE, "LLM 返回空响应");
                }
                return resp;
            } catch (AgentException e) {
                throw e;
            } catch (Exception e) {
                lastError = e;
                if (attempt < maxRetries) {
                    String apiDetail = extractHttpResponseBody(e);
                    log.warn("LLM call failed (attempt {}/{}), retrying in {}ms: {}{}",
                            attempt + 1, maxRetries, RETRY_INTERVAL_MS, e.getMessage(),
                            apiDetail != null ? "\nAPI Response: " + apiDetail : "", e);
                    try {
                        Thread.sleep(RETRY_INTERVAL_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new AgentException(AgentErrorCode.LLM_CALL_FAILED, "LLM 调用重试被中断", ie);
                    }
                }
            }
        }
        String apiDetail = extractHttpResponseBody(lastError);
        log.error("LLM call failed after {} retries: {}{}",
                maxRetries, lastError.getMessage(),
                apiDetail != null ? "\nAPI Response: " + apiDetail : "", lastError);
        throw new AgentException(AgentErrorCode.LLM_CALL_FAILED,
                lastError.getMessage(), lastError);
    }

    /**
     * 构建当前轮次可用的 ChatClient。
     * 当没有 deferredToolRegistry 时返回固定 ChatClient（向后兼容）；
     * 否则从当前 session 获取已发现工具，重建 ChatClient。
     */
    public ChatClient buildRoundChatClient() {
        if (deferredToolRegistry == null) {
            return chatClient;
        }

        List<ToolCallback> roundTools = new ArrayList<>();
        roundTools.addAll(alwaysLoadTools);
        roundTools.addAll(deferredToolSession.getActiveDeferredTools());
        roundTools.add(deferredToolSession.getToolSearchCallback());

        ChatClient.Builder clientBuilder = ChatClient.builder(chatModel);

        if (!advisors.isEmpty()) {
            clientBuilder.defaultAdvisors(advisors.toArray(new Advisor[0]));
        }

        var toolOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(roundTools.toArray(new ToolCallback[0]))
                .internalToolExecutionEnabled(false)
                .build();

        clientBuilder.defaultOptions(toolOptions);
        clientBuilder.defaultToolCallbacks(roundTools.toArray(new ToolCallback[0]));

        return clientBuilder.build();
    }

    /**
     * 处理流式路径的重试/失败逻辑。
     * 统一 scheduleRound 和 forceFinalStream 中的 onErrorResume 重复代码。
     *
     * @param err          异常
     * @param retryAttempt 当前重试次数
     * @param sink         事件接收器
     * @param retryAction  重试时执行的操作
     * @param logLabel     日志标识（如 "LLM stream error"）
     * @return Flux.empty() 供 onErrorResume 使用
     */
    public Flux<ChatResponse> handleStreamError(Throwable err, int retryAttempt,
                                                Sinks.Many<AgentStreamEvent> sink,
                                                Runnable retryAction, String logLabel) {
        if (retryAttempt < maxRetries) {
            String apiDetail = extractHttpResponseBody(err);
            log.warn("{} (attempt {}/{}), retrying in {}ms: {}{}",
                    logLabel, retryAttempt + 1, maxRetries, RETRY_INTERVAL_MS, err.getMessage(),
                    apiDetail != null ? "\nAPI Response: " + apiDetail : "", err);
            sink.tryEmitNext(new AgentStreamEvent.Error(
                    AgentErrorCode.LLM_CALL_FAILED,
                    "LLM 调用失败，正在重试 (" + (retryAttempt + 1) + "/" + maxRetries + ")",
                    err.getMessage()));
            Schedulers.boundedElastic().schedule(retryAction, RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS);
        } else {
            String apiDetail = extractHttpResponseBody(err);
            log.error("{} failed after {} retries: {}{}",
                    logLabel, maxRetries, err.getMessage(),
                    apiDetail != null ? "\nAPI Response: " + apiDetail : "", err);
            sink.tryEmitNext(new AgentStreamEvent.Error(
                    AgentErrorCode.LLM_CALL_FAILED,
                    "LLM 调用失败（已重试 " + maxRetries + " 次）",
                    err.getMessage()));
            sink.tryEmitNext(new AgentStreamEvent.Complete());
            sink.tryEmitComplete();
        }
        return Flux.empty();
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * 从异常链中提取 HTTP 响应体。
     */
    private static String extractHttpResponseBody(Throwable err) {
        Throwable current = err;
        while (current != null) {
            if (current instanceof WebClientResponseException webErr) {
                String body = webErr.getResponseBodyAsString();
                if (body != null && !body.isEmpty()) {
                    return body;
                }
            }
            current = current.getCause();
        }
        return null;
    }
}
