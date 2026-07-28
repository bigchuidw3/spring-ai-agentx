package com.agentx.ai.samples.v1_M2;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.model.ThinkingMode;
import com.agentx.ai.core.tools.BashTool;
import com.agentx.ai.core.tools.FileSystemTools;
import com.agentx.ai.core.tools.GrepTool;
import com.agentx.ai.samples.TestConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import javax.sql.DataSource;

import static com.agentx.ai.core.utils.ToolMergeUtil.mergeTools;

/**
 * SubAgent 测试 — 验证主 Agent 委派任务给子 Agent。
 *
 * <p>两个测试方法：
 * <ul>
 *   <li>{@link #testBlockingCall()} — 非流式：主 Agent call，子 Agent 也 call</li>
 *   <li>{@link #testStreamingCall()} — 流式：主 Agent stream，子 Agent 也 stream，事件带 source 标识</li>
 * </ul>
 *
 * @author bigchui
 */
public class SubAgentTest {

    static ChatModel chatModel;
    static ReactAgent mainAgent;
    static DataSource dataSource;

    public static void init() {
        chatModel = TestConfig.createDeepSeekV4ChatModel();
        dataSource = TestConfig.createMySqlDataSource();

        ToolCallback[] mainTools = BashTool.create();

        mainAgent = ReactAgent.builder()
                .chatModel(chatModel)
                .instructions("你是一个协调员。根据任务类型，将任务委派给合适的专家处理。")
                .dataSource(dataSource)
                .tools(mainTools)
                .maxRounds(40)
                .thinkingMode(ThinkingMode.REASONING_CONTENT)
                // 子 Agent：代码分析专家
                .subAgent(() -> ReactAgent.builder()
                        .name("code-analyzer")
                        .description("代码分析专家，负责分析代码质量、架构和潜在问题")
                        .chatModel(chatModel)
                        .thinkingMode(ThinkingMode.REASONING_CONTENT)
                        .instructions("你是代码分析专家。分析用户提供的代码或项目，给出专业的分析报告。回复要简洁，不超过 200 字。")
                        .tools(mergeTools(BashTool.create(), GrepTool.create(), FileSystemTools.create()))
                        .maxRounds(30)
                        .build())
                // 子 Agent：翻译专家
                .subAgent(() -> ReactAgent.builder()
                        .name("translator")
                        .description("翻译专家，负责中英文翻译")
                        .chatModel(chatModel)
                        .instructions("你是翻译专家。将用户提供的文本统一翻译为日语。只输出翻译结果，不要解释。")
                        .maxRounds(30)
                        .build())
                .build();
    }

    /**
     * 非流式测试：主 Agent call → 子 Agent call。
     * LLM 自动决定委派给 translator 子 Agent 处理翻译任务。
     */
    public static void testBlockingCall() {
        TestConfig.printTestHeader("SubAgent 非流式测试（call）");

        RunnableParams params = TestConfig.buildParams(TestConfig.randomConvId(), TestConfig.randomUserId("user"));
        String result = mainAgent.call("请把这句话翻译成日文：人工智能正在改变软件开发的方式", params);
        System.out.println("最终结果: " + result);
    }

    /**
     * 流式测试：主 Agent stream → 子 Agent stream。
     * 子 Agent 的事件会带 SubAgentSource 标识，前端可区分来源。
     * LLM 自动决定委派给 code-analyzer 子 Agent 分析项目。
     */
    public static void testStreamingCall() {
        TestConfig.printTestHeader("SubAgent 流式测试（stream）");

        System.out.println("Q: 分析一下当前目录下的项目结构\n");

        RunnableParams params = TestConfig.buildParams(TestConfig.randomConvId(), TestConfig.randomUserId("user"));

        mainAgent.streamForResult("请用 code-analyzer 分析当前目录下的项目结构，简要说明这是什么项目", params)
                .doOnNext(TestConfig::printEvent)
                .blockLast();
    }

    // ==================== Main ====================

    public static void main(String[] args) {
        System.out.println("===============================================");
        System.out.println("       SubAgent Test");
        System.out.println("===============================================\n");

        init();

//        testBlockingCall();
        testStreamingCall();

        System.out.println("\n===============================================");
        System.out.println("       Test Completed");
        System.out.println("===============================================");
    }
}
