package com.agentx.ai.samples.v1_1;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.sandbox.SandboxConfig;
import com.agentx.ai.core.sandbox.local.LocalExecutionBackend;
import com.agentx.ai.core.tools.BashTool;
import com.agentx.ai.core.tools.FileSystemTools;
import com.agentx.ai.core.tools.GrepTool;
import com.agentx.ai.samples.TestConfig;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 沙箱机制测试 — 使用 LocalExecutionBackend 在宿主机模拟沙箱行为，无需 Docker。
 *
 * <p>测试项：
 * <ol>
 *   <li>沙箱启用验证 — 工具操作被限定在 workspace 目录内</li>
 *   <li>bash 隔离 — 命令在 workspace 目录下执行</li>
 *   <li>文件读写 — write_file / read_file 走 ExecutionBackend</li>
 *   <li>容器释放验证 — 调用结束后 SandboxHook 正确 release</li>
 * </ol>
 *
 * <p>LocalExecutionBackend 同时实现 SandboxBackend + ExecutionBackend，
 * 命令和文件操作在宿主机 workspace 目录执行，不依赖 Docker。
 *
 * @author bigchui
 */
public class SandboxTest {

    private static final Path WORKSPACE = Path.of("D:/agent-framework/spring-ai-agentx/sandbox-test-workspace");

    private static ReactAgent buildAgent() {
        // 确保 workspace 目录存在
        try {
            Files.createDirectories(WORKSPACE);
        } catch (Exception e) {
            throw new RuntimeException("无法创建 workspace: " + WORKSPACE, e);
        }

        LocalExecutionBackend backend = new LocalExecutionBackend(WORKSPACE.toString());
        SandboxConfig sandboxConfig = SandboxConfig.builder()
                .backend(backend)
                .build();

        List<ToolCallback> tools = new ArrayList<>();
        for (ToolCallback tc : BashTool.create()) tools.add(tc);
        for (ToolCallback tc : FileSystemTools.create()) tools.add(tc);
        for (ToolCallback tc : GrepTool.create()) tools.add(tc);

        return ReactAgent.builder()
                .chatModel(TestConfig.createDeepSeekV4ChatModel())
                .tools(tools)
                .sandbox(sandboxConfig)
                .maxRounds(20)
                .build();
    }

    /**
     * 测试 1：文件写入 + 读取往返
     */
    public static void testFileWriteRead() {
        TestConfig.printTestHeader("沙箱测试 1：文件写入 + 读取");
        ReactAgent agent = buildAgent();
        RunnableParams params = TestConfig.buildParams(
                TestConfig.randomConvId(), TestConfig.randomUserId("sandbox"));

        String query = "请在当前工作目录下创建一个文件 hello.txt，内容写入 'Hello from sandbox!'，"
                + "然后再读取这个文件的内容给我看。";

        System.out.println("Q: " + query);
        System.out.println("Workspace: " + WORKSPACE);

        agent.streamForResult(query, params)
                .doOnNext(TestConfig::printEvent)
                .doOnError(e -> System.err.println("Error: " + e.getMessage()))
                .blockLast();

        // 验证文件是否真的写到了 workspace
        Path target = WORKSPACE.resolve("hello.txt");
        System.out.println("\n--- 验证 ---");
        System.out.println("文件存在: " + Files.exists(target));
        if (Files.exists(target)) {
            try {
                System.out.println("文件内容: " + Files.readString(target));
            } catch (Exception e) {
                System.out.println("读取失败: " + e.getMessage());
            }
        }
    }

    /**
     * 测试 2：bash 命令在沙箱 workspace 内执行
     */
    public static void testBashInWorkspace() {
        TestConfig.printTestHeader("沙箱测试 2：bash 在 workspace 内执行");
        ReactAgent agent = buildAgent();
        RunnableParams params = TestConfig.buildParams(
                TestConfig.randomConvId(), TestConfig.randomUserId("sandbox"));

        String query = "用 bash 执行 'cd' 命令（Windows 用 'cd' 不带参数，Linux 用 'pwd'），"
                + "告诉我当前工作目录是什么。然后再执行 'dir'（Windows）或 'ls'（Linux）列出当前目录内容。";

        System.out.println("Q: " + query);
        System.out.println("Expected workspace: " + WORKSPACE);

        agent.streamForResult(query, params)
                .doOnNext(TestConfig::printEvent)
                .doOnError(e -> System.err.println("Error: " + e.getMessage()))
                .blockLast();
    }

    /**
     * 测试 3：多轮工具调用 — 创建文件后再 grep 搜索
     */
    public static void testGrepAfterWrite() {
        TestConfig.printTestHeader("沙箱测试 3：写入 + grep 搜索");
        ReactAgent agent = buildAgent();
        RunnableParams params = TestConfig.buildParams(
                TestConfig.randomConvId(), TestConfig.randomUserId("sandbox"));

        String query = "请完成以下步骤：\n"
                + "1. 创建文件 test.txt，内容写三行：'apple fruit', 'banana fruit', 'carrot vegetable'\n"
                + "2. 用 grep 工具搜索 'fruit'，告诉我哪些行匹配";

        System.out.println("Q: " + query);

        agent.streamForResult(query, params)
                .doOnNext(TestConfig::printEvent)
                .doOnError(e -> System.err.println("Error: " + e.getMessage()))
                .blockLast();
    }

    /**
     * 测试 4：容器释放验证 — 多次调用，每次都应该 acquire + release
     */
    public static void testContainerReleaseAcrossCalls() {
        TestConfig.printTestHeader("沙箱测试 4：多次调用的容器释放");
        ReactAgent agent = buildAgent();

        for (int i = 1; i <= 3; i++) {
            System.out.println("\n--- 第 " + i + " 次调用 ---");
            RunnableParams params = TestConfig.buildParams(
                    "conv-release-test", TestConfig.randomUserId("sandbox"));

            String query = "用 bash 执行 'echo call-" + i + "' 并告诉我结果。";
            System.out.println("Q: " + query);

            agent.streamForResult(query, params)
                    .doOnNext(TestConfig::printEvent)
                    .doOnError(e -> System.err.println("Error: " + e.getMessage()))
                    .blockLast();
        }

        System.out.println("\n如果三次调用都正常完成且无异常日志，说明容器释放正常。");
    }

    public static void main(String[] args) {
        System.out.println("===============================================");
        System.out.println("       Sandbox Test (LocalExecutionBackend)");
        System.out.println("===============================================");
        System.out.println("Workspace: " + WORKSPACE);
        System.out.println("===============================================");

        int testNumber = 4;
        switch (testNumber) {
            case 1 -> testFileWriteRead();
            case 2 -> testBashInWorkspace();
            case 3 -> testGrepAfterWrite();
            case 4 -> testContainerReleaseAcrossCalls();
            default -> System.out.println("无效的测试编号: " + testNumber);
        }

        System.out.println("\n===============================================");
        System.out.println("       Test Completed");
        System.out.println("===============================================");
    }
}
