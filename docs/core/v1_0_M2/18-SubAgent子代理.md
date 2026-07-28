# SubAgent 子代理

SubAgent 允许主 Agent 将任务委派给专门的子 Agent 处理。每个子 Agent 在独立的 context window 中运行，拥有自己的系统提示词、工具集和推理轮次。主 Agent 的 LLM 根据子 Agent 的描述自动决定是否委派。

## 核心设计

### 工作原理

子 Agent 被包装为 `call_{name}` 工具注册到主 Agent。主 Agent 的 LLM 通过 function calling 自动决定是否调用，调用时传入任务描述，子 Agent 独立执行后返回结果。

```
用户: "翻译这段话"
  ↓
主 Agent (协调员) → LLM 决定委派
  ↓ call_translator(message="翻译这段话")
子 Agent (translator) → 独立 ReAct 循环 → 返回翻译结果
  ↓
主 Agent → 整合结果 → 回复用户
```

### 上下文隔离与共享

| 维度 | 行为 |
|------|------|
| Context Window | **隔离**：子 Agent 拥有独立的消息历史，不继承主 Agent 的对话 |
| System Prompt | **隔离**：子 Agent 使用自己的 `instructions` |
| 工具集 | **隔离**：子 Agent 只能使用显式注册的工具 |
| RunnableParams | **共享**：userId、conversationId 透传给子 Agent |
| DataSource | **不传**：子 Agent 不需要 DataSource，框架自动注入父 Agent 的 TraceStore |

### trace / session / profile memory 行为

子 Agent 的存储行为由框架自动配置，调用方无需关心：

| 功能 | 子 Agent 行为 | 原因 |
|------|--------------|------|
| **agentx_trace** | 跟随父 Agent：父开则子开，父关则子关 | 框架自动注入父 TraceStore |
| **agentx_session** | **永远关闭** | 子 Agent 无 DataSource，不创建 ChatMemory |
| **profile memory** | **永远关闭** | 子 Agent 无 DataSource，不创建 MemoryStore |

## 快速开始

### 注册子 Agent

```java
ReactAgent mainAgent = ReactAgent.builder()
        .chatModel(chatModel)
        .dataSource(dataSource)
        .instructions("你是一个协调员。根据任务类型，将任务委派给合适的专家处理。")
        .maxRounds(40)
        // 子 Agent：代码分析专家
        .subAgent(() -> ReactAgent.builder()
                .name("code-analyzer")
                .description("代码分析专家，负责分析代码质量、架构和潜在问题")
                .chatModel(chatModel)
                .instructions("你是代码分析专家。给出专业的分析报告。")
                .tools(mergeTools(BashTool.create(), GrepTool.create()))
                .maxRounds(5)
                .build())
        // 子 Agent：翻译专家
        .subAgent(() -> ReactAgent.builder()
                .name("translator")
                .description("翻译专家，负责多语言翻译")
                .chatModel(chatModel)
                .instructions("你是翻译专家。只输出翻译结果，不要解释。")
                .maxRounds(3)
                .build())
        .build();
```

### 非流式调用

```java
RunnableParams params = RunnableParams.builder()
        .conversationId("conv_001")
        .userId("user_123")
        .build();

// 主 Agent 自动决定委派给 translator
String result = mainAgent.call("请把这句话翻译成日文：人工智能正在改变软件开发的方式", params);
```

### 流式调用

```java
mainAgent.streamForResult("请用 code-analyzer 分析当前目录下的项目结构", params)
        .doOnNext(event -> {
            // 子 Agent 的事件携带 SubAgentSource 标识
            // 主 Agent 的事件 source 为 null
        })
        .blockLast();
```

## 事件转发与 SubAgentSource

流式模式下，子 Agent 的所有事件（Text、Thinking、ToolStart、ToolEnd 等）会实时转发到主 Agent 的事件流。每个事件携带 `SubAgentSource` 标识，用于区分来源。

### SubAgentSource 结构

```java
public record SubAgentSource(
    String agentName,     // 子 Agent 名称，如 "code-analyzer"
    String subAgentId     // 本次调用的唯一 ID，区分并发场景下的多个实例
) {}
```

### 获取事件来源

通过事件中的 `source()` 方法获取 `SubAgentSource`，null 表示主 Agent 事件。建议在来源切换时打印分隔线，而非给每个 text chunk 加前缀：

```java
// 追踪当前事件来源
String[] currentAgent = {null};

mainAgent.streamForResult("分析项目", params)
        .doOnNext(event -> {
            SubAgentSource source = extractSource(event);
            String agentName = source != null ? source.agentName() : null;

            // 来源切换：打印分隔线
            if (!Objects.equals(agentName, currentAgent[0])) {
                if (currentAgent[0] != null) System.out.println();
                currentAgent[0] = agentName;
                String label = agentName != null ? agentName : "main";
                System.out.println("\n━━━ [" + label + "] ━━━");
            }

            // 按事件类型输出（不再需要前缀）
            switch (event) {
                case AgentStreamEvent.Text t -> System.out.print(t.content());
                case AgentStreamEvent.ToolStart ts ->
                        System.out.println("\n  [ToolStart] " + ts.toolName());
                case AgentStreamEvent.ToolEnd te ->
                        System.out.println("  [ToolEnd] " + te.toolName());
                default -> {}
            }
        })
        .blockLast();

// 辅助方法：从事件中提取 SubAgentSource
private static SubAgentSource extractSource(AgentStreamEvent event) {
    return switch (event) {
        case AgentStreamEvent.Text e -> e.source();
        case AgentStreamEvent.Thinking e -> e.source();
        case AgentStreamEvent.ToolStart e -> e.source();
        case AgentStreamEvent.ToolEnd e -> e.source();
        case AgentStreamEvent.Complete e -> e.source();
        default -> null;
    };
}
```

输出效果：

```
━━━ [main] ━━━
  [ToolStart] call_code-analyzer

━━━ [code-analyzer] ━━━
  [ToolStart] bash
  [ToolEnd] bash → "..."
这是一个基于 Java 的 AI Agent 框架项目...

━━━ [main] ━━━
这是一个基于 Java 的 AI Agent 框架项目...
```

> **提示**：框架内置的 `TestConfig.printEvent(event)` 已自动处理来源切换分隔线，可直接使用：
> ```java
> mainAgent.streamForResult("分析项目", params)
>         .doOnNext(TestConfig::printEvent)
>         .blockLast();
> ```

### 向前兼容

`SubAgentSource` 字段在所有事件中为 `@JsonInclude(NON_NULL)`。主 Agent 的事件 `source` 为 null，序列化后 JSON 中不包含此字段，完全兼容已有前端代码。

## 并发场景

当主 Agent 同时调用多个同类型的子 Agent（例如研讨会场景：3 个分析子 Agent 并行分析），每个实例拥有不同的 `subAgentId`，前端可通过此字段区分。

## 构建约束

子 Agent 通过 `Supplier<ReactAgent>` 提供工厂方法，每次调用创建全新实例（线程安全）。需要注意：

| 约束 | 说明 |
|------|------|
| **必须设置 name** | 子 Agent 的名称，用于生成工具名 `call_{name}` |
| **必须设置 description** | 主 Agent 的 LLM 根据此描述决定是否委派 |
| **禁止嵌套** | 子 Agent 不能再注册 SubAgent |
| **禁止 AskUser** | 子 Agent 不能与用户直接交互 |
| **禁止 PauseAdvisor** | 子 Agent 不能暂停等人工审批 |

### name 和 description 的重要性

`description` 是主 Agent 决定委派的关键依据。框架自动拼接工具描述：

```
{用户设置的 description}。将任务委派给此子Agent处理，它将返回处理结果。
```

例如 `"代码分析专家，负责分析代码质量、架构和潜在问题"` 会变成完整的工具描述，LLM 据此判断何时调用。

## enableXXX 参数体系

ReactAgent Builder 提供三个精细控制开关，均默认为 `true`：

| 参数 | 控制 | 说明 |
|------|------|------|
| `enableSession` | agentx_session（会话历史） | 禁用后不保存对话历史到数据库 |
| `enableTrace` | agentx_trace（调用审计） | 禁用后不记录 LLM 调用链路 |
| `enableProfileMemory` | agentx_memory（用户画像） | 禁用后不提取和注入用户画像 |

```java
ReactAgent agent = ReactAgent.builder()
        .chatModel(chatModel)
        .dataSource(dataSource)
        .enableSession(false)           // 不记录会话历史
        .enableTrace(true)               // 记录调用审计（默认）
        .enableProfileMemory(false)      // 不提取用户画像
        .build();
```

> **注意**：以上参数仅对主 Agent 生效。子 Agent 的行为由框架自动配置，调用方无需设置。
