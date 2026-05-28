# 工具与 MCP

Agent 的核心能力来自工具调用。框架内置了文件系统、Bash、Grep、Python 等通用工具，同时兼容 Spring AI 的自定义工具和 MCP 协议。

工具执行由框架统一接管（`internalToolExecutionEnabled=false`），Agent 在每轮 LLM 推理后判断是否需要调用工具，由框架统一调度执行并将结果返回给 LLM。

## 内置工具

### FileSystemTools — 文件系统操作

提供 5 个文件操作工具：

| 工具名 | 说明 | 关键参数 |
|--------|------|---------|
| `read_file` | 读取文件内容，支持分页 | `filePath`, `offset`, `limit`, `imageFormat` |
| `write_file` | 创建新文件（仅创建，不覆盖） | `filePath`, `content` |
| `edit_file` | 字符串替换编辑文件 | `filePath`, `oldString`, `newString`, `replaceAll` |
| `list_files` | 列出目录内容（非递归） | `path`（可选，默认当前目录） |
| `glob_files` | Glob 模式文件搜索 | `pattern`（如 `**/*.java`） |

```java
ReactAgent agent = ReactAgent.builder()
        .chatModel(chatModel)
        .tools(FileSystemTools.create())
        .build();

// Agent 自动选择合适的工具
agent.call("读取 pom.xml 的前 20 行内容");
```

> `read_file` 默认读取最多 500 行，大文件务必使用分页参数。`write_file` 只创建新文件，不覆盖已存在的文件。`edit_file` 要求精确匹配 `oldString`，支持 `replaceAll` 一次替换所有匹配。

### BashTool — Shell 命令执行

在持久化 Shell 会话中执行命令：

```java
ReactAgent agent = ReactAgent.builder()
        .chatModel(chatModel)
        .tools(BashTool.create())
        .build();
```

**特性**：
- 持久化工作目录和环境变量（`cd` 后目录保持）
- 支持 `restart=true` 清除会话状态
- 输出截断保护（最多 10,000 行 / 100KB）
- 超时控制（默认 2 分钟，可通过 `timeoutMs` 调整）

### GrepTool — 正则搜索

基于正则表达式的文件内容搜索：

```java
ReactAgent agent = ReactAgent.builder()
        .chatModel(chatModel)
        .tools(GrepTool.create())
        .build();
```

**特性**：
- 支持完整正则语法（如 `log.*Error`、`function\s+\w+`）
- 多种输出模式：`content`（匹配行）、`files_with_matches`（文件列表）、`count`（计数）
- 支持上下文行（`-B`/`-A` 选项）
- 通过 `glob` 参数按文件类型过滤（如 `*.java`）
- 分页支持（`headLimit` / `offset`）

### PythonTool — Python 代码执行

基于 GraalVM 的轻量级 Python 执行环境：

```java
ReactAgent agent = ReactAgent.builder()
        .chatModel(chatModel)
        .tools(PythonTool.create())
        .build();
```

**特性**：
- 基于 GraalVM polyglot，无需安装 CPython
- 仅支持标准库（不支持 pip 包）
- 安全限制：无本地访问、无进程创建
- 适用于简单的数据处理和计算任务

> 如需使用 numpy、pandas 等第三方库，建议通过 BashTool 调用系统 Python。

> **依赖说明**：PythonTool 依赖 GraalVM Polyglot 23.1.0 运行时（体积较大），core 模块中已标记为 `optional`。使用前需在项目中显式引入依赖：
>
> ```xml
> <dependency>
>     <groupId>org.graalvm.polyglot</groupId>
>     <artifactId>polyglot</artifactId>
>     <version>${graalvm.version}</version>
> </dependency>
> <dependency>
>     <groupId>org.graalvm.polyglot</groupId>
>     <artifactId>python</artifactId>
>     <version>${graalvm.version}</version>
>     <type>pom</type>
> </dependency>
> ```
>
> 如果不需要 Python 执行能力，无需引入此依赖，不影响框架其他功能正常使用。

### AskUserTool — 用户交互

内置的用户提问工具，配合 PauseAdvisor 实现 Human-in-the-Loop：

```java
ReactAgent agent = ReactAgent.builder()
        .chatModel(chatModel)
        .askUser(true)   // 快捷方式：自动注册工具 + PauseAdvisor
        .build();
```

> 详见 [Human-in-the-Loop](07-Human-in-the-Loop.md)。

## 工具注册

### 多个工具一起注册

使用 `mergeTools` 合并多个工具数组，自动按工具名去重（同名工具保留首次出现）：

```java
import static com.agentx.ai.core.agent.ReactAgent.mergeTools;

ReactAgent agent = ReactAgent.builder()
        .chatModel(chatModel)
        .tools(mergeTools(
                FileSystemTools.create(),
                BashTool.create(),
                GrepTool.create()
        ))
        .build();
```

也可以多次调用 `.tools()` 方法：

```java
ReactAgent agent = ReactAgent.builder()
        .chatModel(chatModel)
        .tools(FileSystemTools.create())
        .tools(BashTool.create())
        .tools(GrepTool.create())
        .build();
```

## 自定义工具

框架完全兼容 Spring AI 的 `@Tool` 注解，任何实现 `ToolCallback` 的类都可直接注册。

### 使用 @Tool 注解

```java
public class OrderTool {

    @Tool(name = "query_order", description = "查询订单信息，返回订单详情")
    public String queryOrder(
            @ToolParam(description = "订单ID") String orderId) {
        return orderService.getById(orderId).toString();
    }

    @Tool(name = "cancel_order", description = "取消指定订单")
    public String cancelOrder(
            @ToolParam(description = "订单ID") String orderId,
            @ToolParam(description = "取消原因") String reason) {
        orderService.cancel(orderId, reason);
        return "订单 " + orderId + " 已取消";
    }
}
```

注册到 Agent：

```java
ReactAgent agent = ReactAgent.builder()
        .chatModel(chatModel)
        .tools(ToolCallbacks.from(new OrderTool()))
        .build();
```

### 使用 ToolCallback 接口

对于需要更灵活控制的场景，可以直接实现 `ToolCallback` 接口：

```java
public class MyCustomTool implements ToolCallback {
    @Override
    public String getName() {
        return "my_tool";
    }

    @Override
    public String getDescription() {
        return "自定义工具描述";
    }

    @Override
    public String call(String toolInput) {
        // 工具逻辑
        return "工具执行结果";
    }
}
```

### 最佳实践

1. **工具描述要清晰**：LLM 根据工具描述决定是否调用，描述不准确会导致错误的工具选择
2. **参数描述要具体**：告诉 LLM 参数的含义和格式，减少传参错误
3. **返回简洁结果**：工具返回内容会成为 LLM 上下文的一部分，过长的结果会消耗更多 Token
4. **注意敏感操作**：对于写文件、删除等不可逆操作，配合 [Human-in-the-Loop](07-Human-in-the-Loop.md) 增加人工确认

## MCP 工具

框架原生支持 Spring AI 的 MCP（Model Context Protocol）协议。MCP 工具可以直接注册到 Agent 中使用，与内置工具和自定义工具统一管理。

> 参考：`AgentSkillsTest` 测试类

## 相关类

| 类 | 包路径 | 说明 |
|----|--------|------|
| `FileSystemTools` | `com.agentx.ai.core.tools` | 文件系统工具集 |
| `BashTool` | `com.agentx.ai.core.tools` | Shell 命令执行 |
| `GrepTool` | `com.agentx.ai.core.tools` | 正则搜索 |
| `PythonTool` | `com.agentx.ai.core.tools` | Python 代码执行 |
| `AskUserTool` | `com.agentx.ai.core.tools` | 用户交互工具 |
| `ToolMergeUtils` | `com.agentx.ai.core.tools` | 工具合并去重 |
| `ReactAgent` | `com.agentx.ai.core.agent` | 工具注册入口 |
