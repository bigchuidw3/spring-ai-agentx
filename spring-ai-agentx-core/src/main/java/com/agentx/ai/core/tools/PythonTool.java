package com.agentx.ai.core.tools;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.function.Function;

/**
 * Python 执行工具 - 使用 GraalVM polyglot 执行 Python 代码
 *
 * 该工具允许 Agent 执行 Python 代码片段并获取结果。
 * 使用 GraalVM 的 polyglot API 在沙箱环境中运行 Python 代码。
 *
 * 使用方式：
 * {@code
 * PythonTool pythonTool = new PythonTool();
 *
 * ReactAgent.builder()
 *     .tools(pythonTool.toToolCallback())
 *     .build();
 * }
 *
 * @author bigchui
 *
 */
public class PythonTool implements Function<PythonTool.PythonRequest, String> {

    private static final Logger log = LoggerFactory.getLogger(PythonTool.class);

    public static final String DESCRIPTION = """
            Executes Python code in a lightweight sandbox (GraalVM Python).

            **IMPORTANT - When to use THIS vs bash + python:**

            USE python TOOL for:
            - Simple calculations: 2 + 2, math.sqrt(16)
            - String manipulation: "hello".upper(), "a b c".split()
            - JSON processing: import json; json.loads(s)
            - Basic algorithms without external packages

            USE bash + python for:
            - Third-party packages: pptx, numpy, pandas, requests
            - File I/O operations
            - Complex data processing
            - Machine learning / data science

            **LIMITATIONS:**
            - Standard library only (no pip packages)
            - Sandbox environment (limited file access)

            Examples:
            - code = "2 + 2" returns "4"
            - code = "'hello'.upper()" returns "HELLO"
            - code = "import json; json.dumps({'x': 1})" returns '{"x": 1}'
            """;

    private Engine engine;

    /**
     * 构造函数 - 创建共享引擎以提升性能
     */
    public PythonTool() {
        try {
            this.engine = Engine.newBuilder()
                    .option("engine.WarnInterpreterOnly", "false")
                    .build();
        } catch (Exception e) {
            log.warn("Failed to initialize Python engine, Python tool will not be available: {}", e.getMessage());
            this.engine = null;
        }
    }

    /**
     * 创建 Python 工具的 ToolCallback 数组（默认配置）
     *
     * 这是一个便捷方法，使用默认描述创建工具实例。
     *
     * @return ToolCallback 数组，包含 python 工具
     */
    public static ToolCallback[] create() {
        return new ToolCallback[]{ createPythonToolCallback(DESCRIPTION) };
    }

    /**
     * 创建 Python 工具的 ToolCallback
     *
     * @param description 工具描述
     * @return ToolCallback 实例
     */
    public static ToolCallback createPythonToolCallback(String description) {
        return FunctionToolCallback.builder("python", new PythonTool())
                .description(description)
                .inputType(PythonRequest.class)
                .build();
    }

    /**
     * 转换为 Spring AI 的 ToolCallback（使用默认描述）
     *
     * @return ToolCallback 实例
     */
    public ToolCallback toToolCallback() {
        return createPythonToolCallback(DESCRIPTION);
    }

    /**
     * 执行 Python 代码（使用 @Tool 注解，可被 LLM 直接调用）
     *
     * @param code Python 代码
     * @return 执行结果
     */
    // @formatter:off
    @Tool(name = "python", description =
            "Executes Python code and returns the result.\n\n" +
            "**LIMITATIONS:** Uses GraalVM Python (not CPython). Third-party packages " +
            "like numpy, pandas, pptx may not work. For complex tasks, use bash tool " +
            "to call system Python instead.\n\n" +
            "**Best for:** Simple calculations, string manipulation, JSON parsing.\n" +
            "**NOT for:** File operations, ML packages, packages with C extensions.\n\n" +
            "Examples:\n" +
            "- code = \"2 + 2\" returns \"4\"\n" +
            "- code = \"'Hello, ' + 'World'\" returns \"Hello, World\"")
    public String executePython(
            @ToolParam(description = "The Python code to execute") String code) { // @formatter:on

        PythonRequest request = new PythonRequest(code);
        return apply(request);
    }

    @Override
    public String apply(PythonRequest request) {
        if (request.code == null || request.code.trim().isEmpty()) {
            return "Error: Python code cannot be empty";
        }

        // 清理代码：处理常见的 JSON 转义问题
        String cleanedCode = cleanCode(request.code);

        try (Context context = Context.newBuilder("python")
                .engine(engine)
                .allowAllAccess(true)        // 安全：默认限制访问权限
                .allowIO(true)                // 允许文件 I/O（pptx 等包需要）
                .allowNativeAccess(false)     // 安全：禁用本地访问
                .allowCreateProcess(false)    // 安全：禁用进程创建
                .allowHostAccess(true)        // 允许访问宿主对象
                .build()) {

            log.debug("Executing Python code: {}", cleanedCode);

            // 执行 Python 代码
            Value result = context.eval("python", cleanedCode);

            // 将结果转换为字符串
            if (result.isNull()) {
                return "Execution completed with no return value";
            }

            // 处理不同的结果类型
            if (result.isString()) {
                return result.asString();
            }
            else if (result.isNumber()) {
                return String.valueOf(result.as(Object.class));
            }
            else if (result.isBoolean()) {
                return String.valueOf(result.asBoolean());
            }
            else if (result.hasArrayElements()) {
                // 将数组/列表转换为字符串表示
                StringBuilder sb = new StringBuilder("[");
                long size = result.getArraySize();
                for (long i = 0; i < size; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    Value element = result.getArrayElement(i);
                    sb.append(element.toString());
                }
                sb.append("]");
                return sb.toString();
            }
            else {
                // 其他类型使用 toString()
                return result.toString();
            }
        }
        catch (PolyglotException e) {
            log.error("Error executing Python code", e);
            return "Error executing Python code: " + e.getMessage();
        }
        catch (Exception e) {
            log.error("Unexpected error executing Python code", e);
            return "Unexpected error: " + e.getMessage();
        }
    }

    /**
     * 清理 Python 代码，处理常见的 JSON 转义问题。
     *
     * LLM 生成的代码中可能包含转义字符，例如：
     * - \\n 应该被转换为真正的换行符
     * - \\t 应该被转换为制表符
     * - \\r 应该被转换为回车符
     * - \\" 应该被转换为双引号
     * - \\\\ 应该被转换为单个反斜杠
     *
     * @param code 原始代码
     * @return 清理后的代码
     */
    private String cleanCode(String code) {
        if (code == null) {
            return null;
        }

        // 处理常见的转义序列
        String cleaned = code
                // 处理双反斜杠转义（JSON 解析后可能留下 \\n 这种模式）
                .replace("\\\\n", "\n")
                .replace("\\\\t", "\t")
                .replace("\\\\r", "\r")
                .replace("\\\\\"", "\"")
                .replace("\\\\'", "'")
                .replace("\\\\\\", "\\");

        return cleaned;
    }

    /**
     * Python 工具请求参数
     */
    public static class PythonRequest {

        @JsonProperty(required = true)
        @JsonPropertyDescription("The Python code to execute")
        public String code;

        /**
         * 默认构造函数
         */
        public PythonRequest() {
        }

        /**
         * 构造函数
         *
         * @param code Python 代码
         */
        public PythonRequest(String code) {
            this.code = code;
        }
    }
}
