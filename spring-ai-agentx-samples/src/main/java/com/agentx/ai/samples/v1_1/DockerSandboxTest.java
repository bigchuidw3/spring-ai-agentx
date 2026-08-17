package com.agentx.ai.samples.v1_1;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.sandbox.IsolationScope;
import com.agentx.ai.core.sandbox.SandboxConfig;
import com.agentx.ai.core.sandbox.docker.DockerBackend;
import com.agentx.ai.core.tools.BashTool;
import com.agentx.ai.core.tools.FileSystemTools;
import com.agentx.ai.core.tools.GrepTool;
import com.agentx.ai.samples.TestConfig;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Docker 沙箱测试 — 需要服务器上已安装 Docker。
 *
 * <p>每个会话获取独立容器，工具调用在容器内执行，真正的文件系统隔离。
 * 调用结束后容器自动销毁，workspace 状态通过 tar 快照持久化。
 *
 * <p>前提条件：
 * <ol>
 *   <li>Docker daemon 已运行</li>
 *   <li>镜像已拉取：docker pull ubuntu:22.04</li>
 * </ol>
 *
 * @author bigchui
 */
public class DockerSandboxTest {

    private static ReactAgent buildAgent() {
        return buildAgent(IsolationScope.CONVERSATION);
    }

    private static ReactAgent buildAgent(IsolationScope scope) {
        SandboxConfig sandboxConfig = SandboxConfig.builder()
                .backend(DockerBackend.builder()
                        .image("ubuntu:latest")
                        .networkDisabled(false)
                        .memoryMb(512)
                        .cpuCount(1)
                        .build())
                .isolationScope(scope)
                .snapshotDir(Path.of(System.getProperty("java.io.tmpdir"), "agentx-snapshots"))
                .build();

        List<ToolCallback> tools = new ArrayList<>();
        for (ToolCallback tc : BashTool.create()) tools.add(tc);
        for (ToolCallback tc : FileSystemTools.create()) tools.add(tc);
        for (ToolCallback tc : GrepTool.create()) tools.add(tc);

        return ReactAgent.builder()
                .chatModel(TestConfig.createDeepSeekV4ChatModel())
                .dataSource(TestConfig.createMySqlDataSource())
                .tools(tools)
                .sandbox(sandboxConfig)
                .maxRounds(10)
                .build();
    }

    /**
     * 测试 1：基础 bash 执行 — 验证命令在容器内运行
     */
    public static void testBashInContainer() {
        TestConfig.printTestHeader("Docker 沙箱测试 1：bash 在容器内执行");
        ReactAgent agent = buildAgent();
        RunnableParams params = TestConfig.buildParams(
                TestConfig.randomConvId(), TestConfig.randomUserId("docker"));

        String query = "执行 uname -a 和 cat /etc/os-release，告诉我你运行在什么系统上。"
                + "然后执行 ls -la /workspace 看看工作目录有什么。";

        System.out.println("Q: " + query);
        agent.streamForResult(query, params)
                .doOnNext(TestConfig::printEvent)
                .doOnError(e -> System.err.println("Error: " + e.getMessage()))
                .blockLast();
    }

    /**
     * 测试 2：文件隔离 — 两个会话写同名文件，互不影响
     */
    public static void testFileIsolation() {
        TestConfig.printTestHeader("Docker 沙箱测试 2：会话间文件隔离");
        ReactAgent agent = buildAgent();

        // 会话 A
        RunnableParams paramsA = TestConfig.buildParams("conv-isolation-a", "user-a");
        String queryA = "创建文件 secret.txt，内容写 '这是会话A的文件'。然后读取它确认内容。";
        System.out.println("\n--- 会话 A ---");
        System.out.println("Q: " + queryA);
        agent.streamForResult(queryA, paramsA)
                .doOnNext(TestConfig::printEvent)
                .doOnError(e -> System.err.println("Error: " + e.getMessage()))
                .blockLast();

        // 会话 B
        RunnableParams paramsB = TestConfig.buildParams("conv-isolation-b", "user-b");
        String queryB = "先读取 secret.txt 看看有什么（应该是找不到或空的）。"
                + "然后创建同名文件 secret.txt，内容写 '这是会话B的文件'。";
        System.out.println("\n--- 会话 B ---");
        System.out.println("Q: " + queryB);
        agent.streamForResult(queryB, paramsB)
                .doOnNext(TestConfig::printEvent)
                .doOnError(e -> System.err.println("Error: " + e.getMessage()))
                .blockLast();

        System.out.println("\n--- 结论 ---");
        System.out.println("会话 B 读 secret.txt 应该失败（不同容器，文件隔离）");
    }

    /**
     * 测试 3：跨调用状态恢复 — 同一会话第二次调用能恢复上次的文件
     */
    public static void testSnapshotRestore() {
        TestConfig.printTestHeader("Docker 沙箱测试 3：快照恢复");
        ReactAgent agent = buildAgent();
        RunnableParams params = TestConfig.buildParams("conv-restore-test", "user-restore");

        // 第一次调用：写文件
        String query1 = "创建文件 data.txt，内容写 '第一次调用写入的数据'。";
        System.out.println("\n--- 第一次调用 ---");
        System.out.println("Q: " + query1);
        agent.streamForResult(query1, params)
                .doOnNext(TestConfig::printEvent)
                .doOnError(e -> System.err.println("Error: " + e.getMessage()))
                .blockLast();

        // 第二次调用：读文件（应该能读到，因为同会话会从快照恢复）
        String query2 = "读取 data.txt 的内容，告诉我里面写了什么。";
        System.out.println("\n--- 第二次调用（同会话）---");
        System.out.println("Q: " + query2);
        agent.streamForResult(query2, params)
                .doOnNext(TestConfig::printEvent)
                .doOnError(e -> System.err.println("Error: " + e.getMessage()))
                .blockLast();

        System.out.println("\n--- 结论 ---");
        System.out.println("第二次调用应该能读到 data.txt（从 tar 快照恢复到新容器）");
    }

    /**
     * 测试 4：bash 安全性 — 尝试访问宿主机文件，应该被容器隔离挡住
     */
    public static void testContainerSecurity() {
        TestConfig.printTestHeader("Docker 沙箱测试 4：容器安全隔离");
        ReactAgent agent = buildAgent();
        RunnableParams params = TestConfig.buildParams(
                TestConfig.randomConvId(), TestConfig.randomUserId("docker"));

        String query = "执行以下命令并告诉我结果：\n"
                + "1. ls / （看容器根目录有什么）\n"
                + "2. cat /etc/hostname（看容器主机名）\n"
                + "3. ls /host 2>&1 或 ls /mnt 2>&1（看能否访问宿主机）\n"
                + "4. whoami（看当前用户）";
        System.out.println("Q: " + query);
        agent.streamForResult(query, params)
                .doOnNext(TestConfig::printEvent)
                .doOnError(e -> System.err.println("Error: " + e.getMessage()))
                .blockLast();

        System.out.println("\n--- 结论 ---");
        System.out.println("容器内只能看到容器自己的文件系统，宿主机文件不可见");
    }

    /**
     * 测试 5：USER 级别隔离 — 同 userId 跨会话共享 workspace 快照
     */
    public static void testUserScopeIsolation() {
        TestConfig.printTestHeader("Docker 沙箱测试 5：USER 级别跨会话恢复");
        ReactAgent agent = buildAgent(IsolationScope.USER);

        // 会话 A：user=shared-user 写文件
        RunnableParams paramsA = TestConfig.buildParams("conv-user-a", "shared-user");
        String queryA = "创建文件 shared.txt，内容写 'USER 级别共享数据'。然后读取确认。";
        System.out.println("\n--- 会话 A (conv=conv-user-a, user=shared-user) ---");
        System.out.println("Q: " + queryA);
        agent.streamForResult(queryA, paramsA)
                .doOnNext(TestConfig::printEvent)
                .doOnError(e -> System.err.println("Error: " + e.getMessage()))
                .blockLast();

        // 会话 B：不同 convId，同 userId → 应从 user 快照恢复，读到 shared.txt
        RunnableParams paramsB = TestConfig.buildParams("conv-user-b", "shared-user");
        String queryB = "读取 shared.txt 的内容，告诉我里面写了什么。"
                + "如果读不到就说找不到。";
        System.out.println("\n--- 会话 B (conv=conv-user-b, user=shared-user) ---");
        System.out.println("Q: " + queryB);
        agent.streamForResult(queryB, paramsB)
                .doOnNext(TestConfig::printEvent)
                .doOnError(e -> System.err.println("Error: " + e.getMessage()))
                .blockLast();

        System.out.println("\n--- 结论 ---");
        System.out.println("会话 B 不同 convId 但同 userId，应能读到 shared.txt（USER 级别快照恢复）");
        System.out.println("对照 CONVERSATION 级别：不同 convId 会互相隔离，读不到。");
    }

    public static void main(String[] args) {
        System.out.println("===============================================");
        System.out.println("       Docker Sandbox Test");
        System.out.println("===============================================");
        System.out.println("ChatModel: " + TestConfig.CHAT_MODEL);
        System.out.println("===============================================");

        int testNumber = 5;
        switch (testNumber) {
            case 1 -> testBashInContainer();
            case 2 -> testFileIsolation();
            case 3 -> testSnapshotRestore();
            case 4 -> testContainerSecurity();
            case 5 -> testUserScopeIsolation();
            default -> System.out.println("无效的测试编号: " + testNumber);
        }

        System.out.println("\n===============================================");
        System.out.println("       Test Completed");
        System.out.println("===============================================");
    }
}
