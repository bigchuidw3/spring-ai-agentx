# Human-in-the-Loop

框架通过 `PauseAdvisor` 拦截指定工具调用，暂停 Agent 执行并返回待处理信息给调用方。调用方根据业务场景决定如何处理，再通过 `resume` 恢复执行。

## 适用场景

- **用户问答**：Agent 需要向用户提问获取信息（如偏好、需求）
- **操作审批**：敏感操作需要人工确认后执行（如写文件、发送邮件）
- **多步确认**：复杂任务中分步骤确认执行计划

## 核心流程

```
callForResult / streamForResult
    │
    ▼
Agent 执行 → 遇到拦截工具 → 暂停 → 返回 Paused
    │                                    │
    │                              调用方处理
    │                              （获取用户输入）
    │                                    │
    │                                    ▼
    │                              resume / resumeStream
    │                                    │
    ▼                                    ▼
继续执行 ← ← ← ← ← ← ← ← ← ← ← ← ←
    │
    ▼
Completed / 再次 Paused（循环）
```

## 工具类型与恢复行为

PauseAdvisor 将拦截的工具分为两类，恢复时采取不同策略：

| 工具类型 | 配置方式 | 用户输入含义 | 恢复行为 |
|---------|---------|------------|---------|
| **用户输入工具** | `askUserTool("ask_user")` | 用户回答即工具结果 | 直接将用户输入作为 ToolResponse 返回给模型 |
| **操作工具** | `approvalTools("write_file")` | 确认或拒绝 | 确认→实际执行工具并返回真实结果；拒绝→返回拒绝信息 |

### 确认与拒绝

- **确认关键词**：`ok`、`yes`、`y`、`好`、`好的`、`确认`、`同意`、`是`、`是的`、`approve`、`confirm`（不区分大小写）
- **拒绝**：输入非确认关键词时，工具不会执行，拒绝信息会返回给模型，模型根据拒绝内容自行决定下一步行为

## PauseState — 暂停状态

Agent 暂停时返回的 `PauseState` 包含完整的执行上下文：

| 字段 | 类型 | 说明 |
|------|------|------|
| `messages` | `List<Message>` | 完整的对话消息历史 |
| `currentRound` | `int` | 当前执行轮次 |
| `pendingToolCalls` | `List<PendingToolCall>` | 待处理的工具调用列表 |
| `params` | `RunnableParams` | 原始调用参数 |

### PendingToolCall — 待处理工具调用

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `String` | 工具调用 ID（用于 resume 时匹配） |
| `name` | `String` | 工具名称 |
| `arguments` | `String` | 工具参数（JSON 格式） |

## 快速模式：askUser(true)

使用内置 AskUserTool 时，`askUser(true)` 是最简配置，自动注册工具 + PauseAdvisor：

```java
ReactAgent agent = ReactAgent.builder()
        .chatModel(chatModel)
        .askUser(true)                    // 等价于 .tools(AskUserTool.create()).advisors(PauseAdvisor.builder().askUserTool("ask_user").build())
        .build();
```

AskUserTool 支持两个参数：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `question` | `String` | 是 | 要问用户的问题 |
| `options` | `List<String>` | 否 | 供用户选择的选项列表，开放性问题不需要提供 |

LLM 会根据问题类型自动判断是否需要提供选项：选择性问题传 options（如方案选择、偏好筛选），开放性问题不传（如预算、需求描述）。

### AskUserTool 两种模式

| 模式 | 创建方式 | 适用场景 | 行为 |
|------|---------|---------|------|
| HITL 模式 | `AskUserTool.create()` | Web 服务、需要异步交互 | 配合 PauseAdvisor，非阻塞暂停 |
| CLI 模式 | `AskUserTool.createBlocking()` | 命令行应用 | 直接阻塞等待 Scanner 输入 |

## 手动模式：PauseAdvisor Builder

需要同时拦截输入工具和操作工具时，使用 PauseAdvisor Builder 精细控制：

```java
Scanner scanner = new Scanner(System.in);

ReactAgent agent = ReactAgent.builder()
        .chatModel(chatModel)
        .tools(AskUserTool.create())           // 注册提问工具
        .tools(FileSystemTools.create())        // 注册文件工具
        .advisors(PauseAdvisor.builder()
                .askUserTool("ask_user")         // 用户输入工具：回答即结果
                .approvalTools("write_file")     // 操作工具：确认后执行
                .build())
        .build();

System.out.print("请输入你的问题: ");
String query = scanner.nextLine();

AgentResult result = agent.callForResult(query, RunnableParams.empty());

while (result instanceof AgentResult.Paused p) {
    Map<String, String> answers = new LinkedHashMap<>();
    for (PendingToolCall ptc : p.state().getPendingToolCalls()) {
        System.out.println("[" + ptc.name() + "] " + ptc.arguments());

        if ("ask_user".equals(ptc.name())) {
            // ask_user：输入工具，用户回答即结果
            System.out.print("你的回答: ");
        } else {
            // write_file 等：操作工具，需要确认或拒绝
            System.out.print("确认执行 " + ptc.name() + "（ok/拒绝）: ");
        }
        answers.put(ptc.id(), scanner.nextLine());
    }

    // 恢复执行：
    // - ask_user → 用户回答作为结果
    // - write_file + "ok" → 实际执行写文件，返回真实结果
    // - write_file + "拒绝" → 不执行，返回拒绝信息给模型
    result = agent.resume(p.state(), answers);
}

if (result instanceof AgentResult.Completed c) {
    System.out.println("A: " + c.answer());
}
```

## 自定义用户输入工具

当内置 AskUserTool 的参数或提示词无法满足业务需求时（如需要自定义交互参数、特殊入参结构、差异化提示词等），可以创建自定义用户输入工具：

```java
// 1. 定义自定义工具（自定义入参结构和提示词）
public class CustomAskTool {

    @Tool(name = "custom_ask", description = """
            向用户提问并提供选项列表供选择。
            在需要用户从多个方案中选择时使用此工具。
            """)
    public String customAsk(
            @ToolParam(description = "要问用户的问题") String question,
            @ToolParam(description = "供用户选择的选项列表") List<String> options) {
        return "此工具需要配合 PauseAdvisor 使用";
    }
}

// 2. 注册工具 + 配置 PauseAdvisor
ToolCallback customAskTool = ToolCallbacks.from(new CustomAskTool())[0];

ReactAgent agent = ReactAgent.builder()
        .chatModel(chatModel)
        .tools(FileSystemTools.create())
        .tools(customAskTool)                                          // 通过 tools() 注册
        .advisors(PauseAdvisor.builder()
                .askUserTool("custom_ask")                              // 标记为用户输入工具
                .approvalTools("write_file")                            // 标记为操作工具
                .build())
        .maxRounds(20)
        .build();
```

PauseAdvisor 通过 `askUserTool` 区分工具类型：
- `askUserTool`：用户回答直接作为工具结果，不需要框架执行
- `approvalTools`：用户确认后由框架实际执行，拒绝则不执行

## 流式暂停恢复

流式路径使用 `streamForResult` + `resumeStream` 实现相同的暂停恢复模式：

```java
Scanner scanner = new Scanner(System.in);
System.out.print("请输入你的问题: ");
String query = scanner.nextLine();

// 首次流式调用
PauseState pauseState = collectStreamEvents(agent.streamForResult(query, RunnableParams.empty()));

// 循环处理暂停
while (pauseState != null) {
    System.out.println("--- Agent 暂停 ---");
    Map<String, String> answers = new LinkedHashMap<>();

    for (PendingToolCall ptc : pauseState.getPendingToolCalls()) {
        System.out.println("[" + ptc.name() + "] " + ptc.arguments());
        if ("ask_user".equals(ptc.name())) {
            System.out.print("你的回答: ");
        } else {
            System.out.print("确认执行 " + ptc.name() + "（ok/拒绝）: ");
        }
        answers.put(ptc.id(), scanner.nextLine());
    }

    // 恢复执行
    pauseState = collectStreamEvents(agent.resumeStream(pauseState, answers));
}

private static PauseState collectStreamEvents(Flux<AgentStreamEvent> flux) {
    PauseState[] holder = new PauseState[1];
    flux.doOnNext(event -> {
        if (event instanceof AgentStreamEvent.Paused p) {
            holder[0] = p.state();
        } else {
            // 处理其他事件（Text, ToolStart, ToolEnd 等）
            printEvent(event);
        }
    }).blockLast();
    return holder[0];
}
```

## 内部实现

- `PauseAdvisor` 同时实现 `CallAdvisor`（同步拦截）和 `StreamAdvisor`（流式拦截）
- 同步路径：拦截 `ChatClientResponse`，在上下文中标记暂停状态
- 流式路径：聚合流式响应后检查是否有需要拦截的工具调用
- 恢复时，框架根据工具类型决定行为：用户输入工具直接注入结果，操作工具确认后执行

## 最佳实践

1. **区分工具类型**：需要用户回答的用 `askUserTool`，需要确认执行的用 `approvalTools`
2. **合理设置 maxRounds**：HITL 场景通常需要更多轮次（多轮提问 + 多轮确认）
3. **处理拒绝场景**：用户拒绝后模型会自行调整方案，确保拒绝信息有意义
4. **Web 场景用 HITL 模式**：不要用 `createBlocking()`，它会阻塞线程
5. **自定义工具时保持返回值**：工具方法的返回值会被 PauseAdvisor 替换，但方法体不能为空

## 相关类

| 类 | 包路径 | 说明 |
|----|--------|------|
| `PauseAdvisor` | `com.agentx.ai.core.advisor` | 暂停顾问，拦截工具调用 |
| `PauseState` | `com.agentx.ai.core.model` | 暂停状态（完整执行上下文） |
| `PendingToolCall` | `com.agentx.ai.core.model` | 待处理工具调用 |
| `AskUserTool` | `com.agentx.ai.core.tools` | 内置用户提问工具 |

> 参考：`HumanInTheLoopTest` 测试类
