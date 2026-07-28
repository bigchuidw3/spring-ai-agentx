package com.agentx.ai.samples.v1_M2;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.tools.BashTool;
import com.agentx.ai.samples.TestConfig;
import org.springframework.ai.chat.model.ChatModel;

/**
 * RequestLoggingAdvisor 测试 — 验证 LLM 入参日志打印。
 * <p>
 * 通过 {@code .requestLogging(true)} 启用内置 RequestLoggingAdvisor，
 * 观察 INFO 日志中的 <code>==================== LLM Request ====================</code> 输出，
 * 确认 messages、model、temperature、tools 均正确序列化。
 *
 * @author bigchui
 */
public class RequestLoggingTest {

    public static void main(String[] args) {
        TestConfig.printTestHeader("RequestLoggingAdvisor 测试");

        ChatModel chatModel = TestConfig.createDeepSeekV4ChatModel();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(BashTool.create())
                .maxRounds(5)
                .requestLogging(true)  // 默认不传则为true，默认开启，flase则手动关闭
                .build();

        // 流式调用 — 会触发工具调用，可以观察多轮 LLM Request 日志
        System.out.println("--- 流式调用（观察日志中的 LLM Request JSON）---");
        TestConfig.streamAndPrint(agent, "执行 date 命令查看当前时间");

        // 同步调用
//        System.out.println("--- 同步调用 ---");
//        String result = agent.call("你好，简单介绍一下你自己");
//        System.out.println("A: " + result);

        System.out.println("\n>>> 测试完成。请查看上方 INFO 日志中的 LLM Request JSON。");
    }
}
