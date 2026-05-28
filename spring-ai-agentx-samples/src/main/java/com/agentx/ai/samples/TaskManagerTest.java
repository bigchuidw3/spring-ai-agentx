package com.agentx.ai.samples;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.agent.internal.AgentTaskManager;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.tools.FileSystemTools;
import org.springframework.ai.chat.model.ChatModel;

/**
 * AgentTaskManager 功能测试。
 * <p>
 * 测试内容：
 * - 测试 1：并发控制 — 同一会话的并发流式请求被拒绝
 * - 测试 2：stopStream — 外部取消正在运行的流式任务
 * - 测试 3：无 TaskManager — 不配置时正常工作，并发不受限
 *
 * @author bigchui
 *
 */
public class TaskManagerTest {

    public static void main(String[] args) {
        ChatModel chatModel = TestConfig.createChatModel();

//        testConcurrentControl(chatModel);
//        testStopStream(chatModel);
        testWithoutTaskManager(chatModel);
    }

    /**
     * 测试 1：同一会话的并发流式请求被拒绝。
     * <p>
     * 启动一个流式请求后，立即用同一 conversationId 发起第二个请求，
     * 第二个请求应该被拒绝（返回 IllegalStateException）。
     */
    static void testConcurrentControl(ChatModel chatModel) {
        TestConfig.printTestHeader("测试 1：并发控制 — 同一会话的并发流式请求被拒绝");

        AgentTaskManager taskManager = new AgentTaskManager();
        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .taskManager(taskManager)
                .build();

        String convId = TestConfig.randomConvId();
        RunnableParams params = TestConfig.buildParams(convId, TestConfig.randomUserId("user"));

        // 启动第一个流（不 blockLast，让它在后台运行）
        System.out.println("启动第一个流式请求: conversationId=" + convId);
        reactor.core.publisher.Flux<String> firstStream = agent.stream("写一首关于春天的五言绝句", params);
        // 订阅但不阻塞
        firstStream.subscribe(
                chunk -> {
                }, // 静默消费
                err -> System.err.println("第一个流出错: " + err.getMessage()),
                () -> System.out.println("第一个流完成")
        );

        // 短暂等待确保第一个流已注册到 taskManager
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 验证 taskManager 中有注册的任务
        System.out.println("当前运行任务数: " + taskManager.getTaskCount());
        System.out.println("会话 " + convId + " 有运行任务: " + taskManager.hasRunningTask(convId));

        // 启动第二个流（同一 conversationId），应该被拒绝
        System.out.println("\n尝试用同一 conversationId 启动第二个流...");
        try {
            agent.stream("写一首关于夏天的五言绝句", params)
                    .doOnError(err -> System.err.println("第二个流被拒绝: " + err.getMessage()))
                    .blockLast();
            System.err.println("失败：第二个流不应该成功执行");
        } catch (IllegalStateException e) {
            System.out.println("成功：第二个流被正确拒绝 — " + e.getMessage());
        }

        // 等待第一个流完成
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("测试后运行任务数: " + taskManager.getTaskCount());
        System.out.println();
    }

    /**
     * 测试 2：外部取消正在运行的流式任务。
     * <p>
     * 启动一个会触发多轮工具调用的流式任务，中途通过 stopStream 取消。
     */
    static void testStopStream(ChatModel chatModel) {
        TestConfig.printTestHeader("测试 2：stopStream — 外部取消流式任务");

        AgentTaskManager taskManager = new AgentTaskManager();
        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .taskManager(taskManager)
                .tools(FileSystemTools.create())
                .build();

        String convId = TestConfig.randomConvId();
        RunnableParams params = TestConfig.buildParams(convId, TestConfig.randomUserId("user"));

        // 启动一个需要较长时间的流式任务
        System.out.println("启动流式任务: conversationId=" + convId);
        var stream = agent.stream("介绍一下四大名著", params);
        stream.subscribe(
                chunk -> System.out.print(chunk),
                err -> System.err.println("\n流被中断: " + err.getMessage()),
                () -> System.out.println("\n流正常完成")
        );

        // 等待任务注册
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 通过 agent 的 API 取消
        System.out.println("\n\n调用 stopStream 取消任务...");
        boolean stopped = agent.stopStream(convId);
        System.out.println("stopStream 结果: " + stopped);
        System.out.println("取消后运行任务数: " + taskManager.getTaskCount());

        // 等待清理完成
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println();
    }

    /**
     * 测试 3：不配置 TaskManager 时流式调用正常工作，并发不受限。
     */
    static void testWithoutTaskManager(ChatModel chatModel) {
        TestConfig.printTestHeader("测试 3：无 TaskManager — 流式正常工作，并发不受限");

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .build();

        String convId = TestConfig.randomConvId();
        RunnableParams params = TestConfig.buildParams(convId, TestConfig.randomUserId("user"));

        System.out.println("启动流式请求（无 TaskManager）...");
        agent.stream("详细介绍一下四大名著", params)
                .doOnNext(chunk -> System.out.print(chunk))
                .doOnError(err -> System.err.println("\nError: " + err.getMessage()))
                .subscribe();

        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        System.out.println("stopStream (无 taskManager): " + agent.stopStream(convId));
        System.out.println("hasRunningTask (无 taskManager): " + agent.hasRunningTask(convId));
        System.out.println("getRunningTaskCount (无 taskManager): " + agent.getRunningTaskCount());
        System.out.println();

        try {
            Thread.sleep(30000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
