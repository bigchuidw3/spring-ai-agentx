package com.agentx.ai.samples;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.tools.BashTool;
import com.agentx.ai.core.tools.FileSystemTools;
import com.agentx.ai.core.tools.GrepTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;

import static com.agentx.ai.core.utils.ToolMergeUtil.mergeTools;

/**
 * RunnableParams 用法测试。
 *
 * <p>验证 RunnableParams 各参数的实际效果：
 * <ul>
 *   <li>测试 1：空参数 — RunnableParams.empty()</li>
 *   <li>测试 2：addParam — 参数注入系统提示词，LLM 可见真实值</li>
 *   <li>测试 3：addParam + addToolParam 配合 — LLM 只看到 default，运行时替换为真实值</li>
 * </ul>
 *
 * @author bigchui
 * 
 */
public class RunnableParamsTest {

    /**
     * 测试 1：基础用法 — 不传任何参数。
     */
    public static void testEmptyParams() {
        TestConfig.printTestHeader("测试 1：空参数");

        ChatModel chatModel = TestConfig.createChatModel();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(BashTool.create())
                .build();

        String answer = agent.call("当前工作目录是什么", RunnableParams.empty());
        System.out.println("A: " + answer);
    }

    /**
     * 测试 2：addParam — 参数注入系统提示词，LLM 可见真实值。
     *
     * <p>addParam("language", "zh-CN") 会将 "language: zh-CN" 注入系统提示词，
     * LLM 能看到真实值并直接使用，不需要通过工具参数替换。
     */
    public static void testAddParamOnly() {
        TestConfig.printTestHeader("测试 2：addParam — LLM 可见真实值");

        ChatModel chatModel = TestConfig.createChatModel();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .build();

        // 系统提示词中注入：language: zh-CN（LLM 直接看到真实值）
        RunnableParams params = RunnableParams.builder()
                .addParam("language", "zh-CN")
                .addParam("style", "简洁专业")
                .build();

        String answer = agent.call("介绍一下 Spring AI 框架", params);
        System.out.println("A: " + answer);
    }

    /**
     * 测试 3：addParam + addToolParam 配合 — LLM 只看到 default，运行时替换。
     *
     * <p>当 addParam 和 addToolParam 的 key 相同时：
     * - 系统提示词中显示 "path: default"（隐藏真实路径，避免 LLM 幻觉）
     * - 工具执行时自动将 "default" 替换为真实路径
     *
     * <p>适用于参数值过长或复杂，LLM 容易传错的场景。
     */
    public static void testParamWithToolParam() {
        TestConfig.printTestHeader("测试 3：addParam + addToolParam — 隐藏真实值");

        ChatModel chatModel = TestConfig.createChatModel();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(mergeTools(FileSystemTools.create(), GrepTool.create(), ToolCallbacks.from(new TestTools())))
                .thinkTagEnabled(false)
                .build();

        RunnableParams params = RunnableParams.builder()
                // path 同时在 addParam 和 addToolParam 中声明 → 提示词显示 "path: default"
                .addParam("xdrToken", "123556777221fdqewqewqewqttq77")
                .addToolParam("xdrToken", "123556777221fdqewqewqewqttq77")
                // language 只在 addParam 中声明 → 提示词显示 "language: zh-CN"（LLM 可见）
                .addParam("language", "zh-CN")
                .build();

        // 系统提示词注入结果：
        // ## 系统参数（可用于工具调用）
        // path: default        ← 隐藏真实值，运行时替换
        // language: zh-CN       ← LLM 直接可见

        String answer = agent.call("根据token获取项目的详细信息？", params);
        System.out.println("A: " + answer);
    }

    /**
     * 测试 4：会话管理 — conversationId + userId。
     */
    public static void testSessionParams() {
        TestConfig.printTestHeader("测试 4：会话参数");

        ChatModel chatModel = TestConfig.createChatModel();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .askUser(true)
                .build();

        // conversationId：会话标识，用于 ChatMemory 和任务管理
        // userId：用户标识，用于长期记忆维度
        RunnableParams params = RunnableParams.builder()
                .conversationId("conv_001")
                .userId("user_zhangsan22")
                .addParam("userId","user_zhangsan")
                .build();

        String answer = agent.call("你好，我是张三，Java 开发工程师，我的userId是多少", params);
        System.out.println("A: " + answer);
    }

    public static void main(String[] args) {
        System.out.println("===============================================");
        System.out.println("       RunnableParams Test");
        System.out.println("===============================================");
        System.out.println("ChatModel: " + TestConfig.CHAT_MODEL);
        System.out.println("===============================================");

        int testNumber = 3;

        switch (testNumber) {
            case 1 -> testEmptyParams();
            case 2 -> testAddParamOnly();
            case 3 -> testParamWithToolParam();
            case 4 -> testSessionParams();
            default -> System.out.println("无效的测试编号: " + testNumber);
        }

        System.out.println("\n===============================================");
        System.out.println("       Test Completed");
        System.out.println("===============================================");
    }
}
