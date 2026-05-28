package com.agentx.ai.core.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * Bash Tool - Shell 命令执行工具
 *
 * @author bigchui
 *
 */
public class BashTool {

    private static final Logger log = LoggerFactory.getLogger(BashTool.class);
    private static final String DEFAULT_SESSION_ID = "default";

    private final ShellSessionManager sessionManager;
    private final long defaultTimeoutMs;
    private final int maxLines;
    private final int maxBytes;

    private BashTool(Builder builder) {
        this.sessionManager = builder.sessionManager != null
            ? builder.sessionManager
            : ShellSessionManager.builder()
                .maxLines(builder.maxLines)
                .maxBytes(builder.maxBytes)
                .timeoutMs(builder.timeoutMs)
                .build();
        this.defaultTimeoutMs = builder.timeoutMs;
        this.maxLines = builder.maxLines;
        this.maxBytes = builder.maxBytes;
    }

    /**
     * 创建 Bash 工具的 ToolCallback 数组（默认配置）
     *
     * 这是一个便捷方法，使用默认配置创建工具实例。
     * 工具描述会根据当前操作系统动态生成，确保 LLM 生成正确的命令。
     *
     * @return ToolCallback 数组，包含 bash 工具
     */
    public static ToolCallback[] create() {
        BashTool tool = builder().build();
        String dynamicDescription = buildDynamicDescription();
        return new ToolCallback[]{
            FunctionToolCallback.builder("bash", new BashToolFunction(tool))
                .description(dynamicDescription)
                .inputType(BashToolFunction.Request.class)
                .build()
        };
    }

    /**
     * 构建动态工具描述（根据当前操作系统）
     *
     * @return 工具描述字符串
     */
    private static String buildDynamicDescription() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String osType = isWindows ? "Windows (cmd.exe)" : "Unix/Linux/Mac (bash)";

        String osNotes;
        if (isWindows) {
            osNotes = """
                    **Windows 命令注意事项:**
                    - 用 'type' 代替 'cat'
                    - 用 'dir' 代替 'ls'
                    - 用 'copy' 或 'type nul > file' 代替 'touch'
                    - 用 'mkdir dirname'（不要加 -p！Windows 的 mkdir 自动创建父目录）
                    - 绝对不要用 'mkdir -p'，这会创建一个名为 '-p' 的文件夹！
                    - 用 'del' 代替 'rm'
                    - 用 'move' 代替 'mv'
                    - 用 'findstr' 代替 'grep'
                    - 路径使用反斜杠: C:\\Users\\...
                    - 用 '&&' 或 '&' 连接多条命令

                    **Python 注意事项（重要）:**
                    - 复杂代码或第三方库：先写入 .py 文件，再执行 python script.py
                    - 正确写法: python -c "open('s.py','w',encoding='utf-8').write('''代码''')" && python s.py
                    - 避免: echo '中文' > file.py（编码错误！）""";
        } else {
            osNotes = """
                    **Unix/Linux/Mac 命令注意事项:**
                    - 使用标准命令: ls, cat, grep, find, touch, mkdir, rm 等
                    - 'mkdir -p' 可自动创建父目录
                    - 路径使用正斜杠: /home/user/...
                    - 用 '&&' 或 ';' 连接多条命令""";
        }

        return """
                在持久化 Shell 会话中执行命令。

                **当前操作系统: %s**

                %s

                适用场景:
                - 执行 Shell 命令、系统命令、git 操作
                - 运行 Python 脚本: python script.py
                - 安装包、构建工具等

                Shell 会话特性:
                - 工作目录持久化（用 'cd' 切换）
                - 环境变量持久化
                - 设置 restart=true 可清除会话状态

                ⚠️ 工具优先级（重要）:
                本工具是最后的手段，请优先使用专用工具:
                - 读取文件 → read_file（不要用 cat/head/tail）
                - 编辑文件 → edit_file（不要用 sed/awk）
                - 写入文件 → write_file（不要用 echo/cat 重定向）
                - 搜索文件 → glob_files（不要用 find/ls）
                - 搜索内容 → grep（不要用 grep/rg 命令）""".formatted(osType, osNotes);
    }

    /**
     * 创建 Builder
     *
     * @return Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 执行 Shell 命令
     *
     * 此方法被标记为 @Tool，可被 LLM 调用。
     * 支持通过 restart 参数重启会话。
     *
     * @param command        要执行的命令
     * @param restart        是否重启会话（可选，默认 false）
     * @param timeoutMs      超时时间（可选，默认使用配置值）
     * @return 命令执行结果
     */
    // @formatter:off
    @Tool(name = "bash", description = """
            在持久化 Shell 会话中执行命令。

            适用场景:
            - 执行 Shell 命令（bash、sh、Windows 上为 cmd.exe）
            - 与文件系统交互
            - 执行构建工具、git 命令等
            - 运行脚本和程序

            Shell 会话特性:
            - 工作目录持久化（用 'cd' 切换）
            - 环境变量持久化

            使用建议:
            - 尽量使用绝对路径
            - 用 '&&' 或 ';' 连接多条命令
            - 设置 restart=true 可清除会话状态

            注意事项:
            - 本工具拥有完整系统权限，请谨慎使用
            - 文件操作请优先使用专用工具: read_file, write_file, edit_file, glob_files, grep
            - 不要用本工具执行: find, grep, cat, head, tail, sed, awk, echo（除非有特殊指示）""")
    public String executeShellCommand(
            @ToolParam(description = "【必填】要执行的 Shell 命令") String command,
            @ToolParam(description = "是否在执行前重启 Shell 会话（默认 false）", required = false) Boolean restart,
            @ToolParam(description = "超时时间（毫秒，默认 120000）", required = false) Long timeoutMs) { // @formatter:on

        log.debug("BashTool called with command: {}, restart: {}", command, restart);

        // 使用默认会话 ID
        String sessionId = DEFAULT_SESSION_ID;

        // 处理会话重启
        if (restart != null && restart) {
            log.debug("Restarting shell session: {}", sessionId);
            sessionManager.removeSession(sessionId);
        }

        // 执行命令
        try {
            ShellSessionManager.CommandResult result = sessionManager.executeCommand(
                sessionId,
                command,
                null  // 工作目录由会话管理器自动处理
            );

            // 格式化输出
            return formatResult(result);
        } catch (Exception e) {
            log.error("Error executing command: {}", e.getMessage(), e);
            return "Error executing command: " + e.getMessage();
        }
    }

    /**
     * 简化版执行方法（直接调用）
     *
     * @param command 命令
     * @return 执行结果
     */
    public String executeShellCommand(String command) {
        return executeShellCommand(command, null, null);
    }

    /**
     * 格式化命令执行结果
     */
    private String formatResult(ShellSessionManager.CommandResult result) {
        List<String> parts = new ArrayList<>();

        if (result.output() != null && !result.output().isEmpty()) {
            parts.add(result.output());
        }
        if (result.error() != null && !result.error().isEmpty()) {
            parts.add("STDERR:\n" + result.error());
        }
        if (result.exitCode() != 0) {
            parts.add("[Exit code: " + result.exitCode() + "]");
        }

        return String.join("\n", parts);
    }

    /**
     * 获取会话管理器
     *
     * @return ShellSessionManager
     */
    public ShellSessionManager getSessionManager() {
        return sessionManager;
    }

    /**
     * Builder 模式
     */
    public static class Builder {
        private ShellSessionManager sessionManager;
        private long timeoutMs = 120000;  // 默认 2 分钟
        private int maxLines = 10000;     // 默认最大 10000 行
        private int maxBytes = 100000;    // 默认最大 100KB

        /**
         * 设置 ShellSessionManager
         *
         * @param sessionManager 会话管理器
         * @return this
         */
        public Builder sessionManager(ShellSessionManager sessionManager) {
            this.sessionManager = sessionManager;
            return this;
        }

        /**
         * 设置超时时间（毫秒）
         *
         * @param timeoutMs 超时时间
         * @return this
         */
        public Builder timeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        /**
         * 设置最大行数限制
         *
         * @param maxLines 最大行数
         * @return this
         */
        public Builder maxLines(int maxLines) {
            this.maxLines = maxLines;
            return this;
        }

        /**
         * 设置最大字节数限制
         *
         * @param maxBytes 最大字节数
         * @return this
         */
        public Builder maxBytes(int maxBytes) {
            this.maxBytes = maxBytes;
            return this;
        }

        /**
         * 构建 BashTool
         *
         * @return BashTool 实例
         */
        public BashTool build() {
            return new BashTool(this);
        }
    }

    /**
     * Bash 工具的 Function 包装类。
     *
     * 用于 FunctionToolCallback，支持动态描述。
     */
    private static class BashToolFunction implements java.util.function.Function<BashToolFunction.Request, String> {

        private final BashTool bashTool;

        BashToolFunction(BashTool bashTool) {
            this.bashTool = bashTool;
        }

        @Override
        public String apply(Request request) {
            return bashTool.executeShellCommand(
                request.command(),
                request.restart(),
                request.timeoutMs()
            );
        }

        /**
         * Bash 工具请求
         */
        record Request(
            String command,
            Boolean restart,
            Long timeoutMs
        ) {}
    }
}
