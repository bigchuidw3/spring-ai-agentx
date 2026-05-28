package com.agentx.ai.samples;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.tools.BashTool;
import com.agentx.ai.core.tools.FileSystemTools;
import com.agentx.ai.core.tools.GrepTool;
import com.agentx.ai.core.tools.SkillsTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import static com.agentx.ai.core.utils.ToolMergeUtil.mergeTools;

/**
 * Agent Skills 集成测试。
 *
 * <p>测试内容：
 * <ul>
 *   <li>测试 1：基础 call（无工具，验证 Agent 基本可用）</li>
 *   <li>测试 2：Stream + 文件系统工具</li>
 *   <li>测试 3：Stream + Skills 按需加载</li>
 *   <li>测试 4：Stream + 全量工具 + Skills</li>
 * </ul>
 *
 * @author bigchui
 * 
 */
public class AgentSkillsTest {

    // ===== 测试方法 =====

    /**
     * 测试 1：基础 call（无工具）。
     */
    public static void testBasicCall() {
        TestConfig.printTestHeader("测试 1：基础 call（无工具）");

        ChatModel chatModel = TestConfig.createMiniMaxChatModel();
        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(mergeTools(
                        new ToolCallback[]{SkillsTool.builder().addSkillsDirectory(TestConfig.SKILLS_DIR).build()}))
                .build();

        String query = "使用：WebShell 检测与恶意代码分析 这个skill。帮我分析下，这个代码有没有风险：ewqeqgfwqewqewqewqewqewqmlllll22221";
        System.out.println("Q: " + query);
        String response = agent.call(query);
        System.out.println("A: " + response);
    }

    /**
     * 测试 2：Stream + 文件系统工具。
     */
    public static void testStreamWithTools() {
        TestConfig.printTestHeader("测试 2：Stream + 文件系统工具");

        ChatModel chatModel = TestConfig.createChatModel();
        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(mergeTools(FileSystemTools.create(), GrepTool.create()))
                .maxRounds(10)
                .build();

        TestConfig.streamAndPrint(agent, """
                请在当前目录创建一个 skills_test.txt 文件，
                内容写上 'Skills 测试文件，创建成功'，
                然后读取文件确认内容正确。
                """);
    }

    /**
     * 测试 3：Stream + Skills 按需加载。
     */
    public static void testStreamWithSkills() {
        TestConfig.printTestHeader("测试 3：Stream + Skills 按需加载");

        ChatModel chatModel = TestConfig.createChatModel();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(mergeTools(FileSystemTools.create(),
                        new ToolCallback[]{SkillsTool.builder().addSkillsDirectory(TestConfig.SKILLS_DIR).build()}))
                .build();

        TestConfig.streamAndPrint(agent, "帮我想一些关于《火影忍者》的域名，www开头，cn结尾，使用\"域名创意生成器\"这个skill");
    }

    /**
     * 测试 4：Stream + 全量工具 + Skills。
     */
    public static void testStreamWithAllToolsAndSkills() {
        TestConfig.printTestHeader("测试 4：Stream + 全量工具 + Skills");

        ChatModel chatModel = TestConfig.createChatModel();

        ToolCallback[] allTools = mergeTools(
                BashTool.create(),
                FileSystemTools.create(),
                GrepTool.create(),
                new ToolCallback[]{SkillsTool.builder().addSkillsDirectory(TestConfig.SKILLS_DIR).build()}
        );

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .tools(allTools)
                .maxRounds(100)
                .build();

        TestConfig.streamAndPrint(agent, "帮我使用 ppt skills，做一个关于人工智能发展趋势主题的 PPT，5 页，科技风，中文，深色背景");

//        TestConfig.streamAndPrint(agent, "帮我在D:\\games，下面，写一个html小游戏，贪吃蛇，卡通风格，要有游戏开始，结束，暂停，计分，下一关等功能，" +
//                "分为html,css,js文件开发，可以直接点击html启动即可");

//        TestConfig.streamAndPrint(agent, "使用fireworks-tech-graph，画一张RAG流程图");

//        TestConfig.streamAndPrint(agent,"使用vulnerability-scanner 帮我扫描一下192.168.11.163这个机器，nmap本机器已经安装，无需安装，输出一份分析报告");
    }

    // ===== Main =====

    public static void main(String[] args) {
        System.out.println("===============================================");
        System.out.println("       Agent Skills Test");
        System.out.println("===============================================");
        System.out.println("ChatModel: " + TestConfig.CHAT_MODEL);
        System.out.println("Skills:    " + TestConfig.SKILLS_DIR);
        System.out.println("===============================================");

        int testNumber = 1;

        switch (testNumber) {
            case 1 -> testBasicCall();
            case 2 -> testStreamWithTools();
            case 3 -> testStreamWithSkills();
            case 4 -> testStreamWithAllToolsAndSkills();
            default -> System.out.println("无效的测试编号: " + testNumber);
        }

        System.out.println("\n===============================================");
        System.out.println("       Test Completed");
        System.out.println("===============================================");
    }
}
