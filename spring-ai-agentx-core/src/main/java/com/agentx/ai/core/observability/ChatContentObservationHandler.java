package com.agentx.ai.core.observability;

import com.agentx.ai.core.utils.MessageJsonSerializer;
import com.alibaba.fastjson2.JSON;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.observation.ChatClientObservationContext;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM span 内容导出 Handler — 让每个 LLM span 自带 input（prompt 原文）与
 * output（completion 原文或 tool_calls 指令），符合 OpenInference /
 * GenAI 语义约定的主流形态。
 *
 * <p>背景：Spring AI 1.1.0 默认只导出元数据（模型名/token/耗时），
 * prompt/completion 需自行补齐；{@code log-prompt/log-completion} 仅控制日志，不进 span。
 *
 * <p>两个实现要点（实践验证过，缺一不可）：
 * <ul>
 * <li>必须用 ObservationHandler 而非全局 Convention——后者会被 ChatModel
 *     显式传入的默认 Convention 整体顶掉</li>
 * <li>必须 {@link Ordered}{@code #getOrder()} 返回 {@code HIGHEST_PRECEDENCE}——
 *     否则 tracing handler 先执行 onStop 消费掉属性快照，后写入的内容丢失</li>
 * </ul>
 *
 * <p>注册方式（宿主一次性全局注册）：
 * <pre>{@code
 * observationRegistry.observationConfig()
 *     .observationHandler(new ChatContentObservationHandler());
 * }</pre>
 *
 * @author bigchui
 */
public class ChatContentObservationHandler implements ObservationHandler<Observation.Context>, Ordered {

    /** OpenInference 约定的输入属性，Opik 映射到 span 的 Input 标签页。 */
    public static final String KEY_INPUT_VALUE = "input.value";
    /** OpenInference 约定的输出属性，Opik 映射到 span 的 Output 标签页。 */
    public static final String KEY_OUTPUT_VALUE = "output.value";

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ChatModelObservationContext || context instanceof ChatClientObservationContext;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void onStop(Observation.Context context) {
        try {
            if (context instanceof ChatModelObservationContext chatModelCtx) {
                onStopChatModel(chatModelCtx);
            } else if (context instanceof ChatClientObservationContext chatClientCtx) {
                onStopChatClient(chatClientCtx);
            }
        } catch (Exception ignored) {
            // 内容导出失败不影响主流程与 span 本身
        }
    }

    private void onStopChatModel(ChatModelObservationContext context) {
        if (context.getRequest() != null && context.getRequest().getInstructions() != null) {
            context.addHighCardinalityKeyValue(KeyValue.of(KEY_INPUT_VALUE,
                    MessageJsonSerializer.toJson(context.getRequest().getInstructions())));
        }
        String completion = completionContent(context.getResponse());
        if (completion != null) {
            context.addHighCardinalityKeyValue(KeyValue.of(KEY_OUTPUT_VALUE, completion));
        }
    }

    /**
     * ChatClient 层兜底：流式调用时 ChatModel 层聚合文本可能缺失（token 聚得上、
     * 文本聚不上），而 ChatClient 层聚合器（aggregateChatClientResponse）
     * 的 setResponse 先于 observation.stop()，流式响应完整。
     */
    private void onStopChatClient(ChatClientObservationContext context) {
        if (context.getRequest() != null && context.getRequest().prompt() != null
                && context.getRequest().prompt().getInstructions() != null) {
            context.addHighCardinalityKeyValue(KeyValue.of(KEY_INPUT_VALUE,
                    MessageJsonSerializer.toJson(context.getRequest().prompt().getInstructions())));
        }
        ChatClientResponse response = context.getResponse();
        String completion = response != null ? completionContent(response.chatResponse()) : null;
        if (completion != null) {
            context.addHighCardinalityKeyValue(KeyValue.of(KEY_OUTPUT_VALUE, completion));
        }
    }

    private String completionContent(ChatResponse response) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        List<Map<String, String>> toolCalls = new ArrayList<>();
        for (var generation : response.getResults()) {
            if (generation.getOutput() == null) {
                continue;
            }
            if (generation.getOutput().getText() != null) {
                sb.append(generation.getOutput().getText());
            }
            if (generation.getOutput().getToolCalls() != null) {
                for (var call : generation.getOutput().getToolCalls()) {
                    // arguments 为模型输出的原始 JSON 字符串，原样保留
                    toolCalls.add(Map.of("name", call.name(),
                            "arguments", call.arguments() == null ? "" : call.arguments()));
                }
            }
        }
        if (sb.isEmpty() && !toolCalls.isEmpty()) {
            // 工具调用轮：模型不输出正文、只发出 tool_calls 指令——记录指令本身
            return JSON.toJSONString(toolCalls);
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}
