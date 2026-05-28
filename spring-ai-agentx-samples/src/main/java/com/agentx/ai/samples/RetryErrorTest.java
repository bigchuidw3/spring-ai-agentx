package com.agentx.ai.samples;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.exception.AgentException;
import com.agentx.ai.core.model.AgentResult;
import com.agentx.ai.core.model.RunnableParams;

/**
 * 异常重试测试 — 验证 LLM 调用失败时的重试机制和错误处理。
 * <p>
 * 使用错误的 API Key 触发 401 Unauthorized 错误，验证：
 * <ul>
 *   <li>call() — 抛出 AgentException</li>
 *   <li>callForResult() — 返回 AgentResult.Failed</li>
 *   <li>stream() — 流静默结束（Error 事件被过滤）</li>
 *   <li>streamForResult() — 发出 Error 事件 + Complete 正常结束</li>
 * </ul>
 *
 * @author bigchui
 * 
 */
public class RetryErrorTest {

    public static void main(String[] args) {
        // 使用错误的 API Key 创建 ChatModel（触发 401 错误）
        ReactAgent agent = ReactAgent.builder()
                .chatModel(TestConfig.createMiniMaxChatModel())
                .maxRetries(2)
                .build();

        TestConfig.printTestHeader("1. call() — 应抛出 AgentException");
        testCall(agent);

//        TestConfig.printTestHeader("2. callForResult() — 应返回 AgentResult.Failed");
//        testCallForResult(agent);
//
//        TestConfig.printTestHeader("3. stream() — Error 事件被过滤，流静默结束");
//        testStream(agent);
//
//        TestConfig.printTestHeader("4. streamForResult() — 应发出 Error + Complete 事件");
//        testStreamForResult(agent);
    }

    private static void testCall(ReactAgent agent) {
        try {
            String answer = agent.call("你好");
            System.out.println("unexpected success: " + answer);
        } catch (AgentException e) {
            System.out.println("caught AgentException: code=" + e.getCode() + ", message=" + e.getMessage());
        }
    }

    private static void testCallForResult(ReactAgent agent) {
        AgentResult result = agent.callForResult("你好", RunnableParams.empty());

        if (result.isFailed()) {
            AgentResult.Failed failed = (AgentResult.Failed) result;
            System.out.println("result type: Failed");
            System.out.println("error code: " + failed.code());
            System.out.println("error msg:  " + failed.error());
        } else {
            System.out.println("unexpected result type: " + result);
        }
    }

    private static void testStream(ReactAgent agent) {
        StringBuilder buffer = new StringBuilder();
        agent.stream("你好")
                .doOnNext(chunk -> {
                    buffer.append(chunk);
                    System.out.print(chunk);
                })
                .doOnComplete(() -> {
                    System.out.println("\n[stream completed]");
                    if (buffer.isEmpty()) {
                        System.out.println("no text emitted (expected — Error events filtered out in Flux<String>)");
                    }
                })
                .blockLast();
    }

    private static void testStreamForResult(ReactAgent agent) {
        agent.streamForResult("你好", RunnableParams.empty())
                .doOnNext(TestConfig::printEvent)
                .blockLast();
    }
}
