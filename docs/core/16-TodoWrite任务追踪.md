# TodoWrite 任务追踪

TodoWrite 是一个结构化任务列表工具，灵感来源于 Claude Code 的同名工具。它让 AI 智能体在处理复杂多步骤任务时，能主动创建、追踪和更新任务列表，将隐式规划转为显式、可观测的工作流。

## 为什么需要 TodoWrite

没有任务列表时，智能体在处理多步骤任务时容易出现：
- **步骤遗漏**：中间步骤被跳过，导致结果不完整
- **执行混乱**：多个任务穿插执行，缺乏顺序感
- **进度不可见**：外部无法感知当前执行到哪一步

TodoWrite 通过强制 LLM 在执行前后更新任务状态，解决了这些问题。

## 快速开始

```java
import com.agentx.ai.core.tools.TodoWriteTool;

ReactAgent agent = ReactAgent.builder()
        .chatModel(chatModel)
        .tools(mergeTools(
                TodoWriteTool.create(),
                BashTool.create(),
                FileSystemTools.create()
        ))
        .build();

agent.call("帮我分析当前项目的代码结构，生成一份架构总结报告");
```

注册 `TodoWriteTool.create()` 后，LLM 在处理 3 步以上的复杂任务时，会自动：
1. 先调用 TodoWrite 创建任务列表（全部 pending）
2. 逐步将任务标记为 in_progress → completed
3. 如果中途发现新步骤，会动态添加到列表

## 任务状态

每个任务项包含三个字段：

| 字段 | 类型 | 说明 | 示例 |
|------|------|------|------|
| `content` | String | 祈使形式，描述需要做什么 | `"运行测试"` |
| `activeForm` | String | 现在进行时形式，执行时显示 | `"正在运行测试"` |
| `status` | Enum | 任务状态 | `pending` / `in_progress` / `completed` |

状态流转：`pending` → `in_progress` → `completed`

## 校验规则

框架内置校验，不合法的任务列表会直接报错，LLM 会收到错误信息并自行修正：

| 规则 | 说明 |
|------|------|
| 同一时间只能有一个 `in_progress` | 必须先完成当前任务才能开始下一个 |
| `content` 不能为空 | 所有任务必须有有意义的描述 |
| `activeForm` 不能为空 | 所有任务必须提供执行时显示文本 |
| `status` 不能为 null | 必须是 `pending`、`in_progress`、`completed` 之一 |

## 流式进度事件

流式调用时，框架在每次 TodoWrite 执行后自动发射 `TodoProgress` 事件，无需手动处理：

```java
agent.streamForResult(query, RunnableParams.empty())
        .doOnNext(event -> {
            if (event instanceof AgentStreamEvent.TodoProgress tp) {
                // tp.items() — 当前所有任务项
                for (TodoWriteTool.TodoItem item : tp.items()) {
                    System.out.printf("[%s] %s%n", item.status(), item.content());
                }
            }
        })
        .blockLast();
```

前端可订阅此事件实时渲染任务进度面板。

> `TodoProgress` 事件仅流式路径可用。非流式路径（`call` / `callForResult`）不发射此事件。

## 典型工作流

以「添加深色模式开关并运行测试」为例，LLM 的完整调用链路：

```
用户：帮我添加深色模式开关，完成后运行测试

1. TodoWrite → [添加深色模式开关(pending), 实现主题切换(pending), 运行测试(pending)]
2. TodoWrite → [添加深色模式开关(in_progress), 实现主题切换(pending), 运行测试(pending)]
3. write_file → 创建开关组件
4. TodoWrite → [添加深色模式开关(completed), 实现主题切换(in_progress), 运行测试(pending)]
5. edit_file → 添加主题切换逻辑
6. TodoWrite → [添加深色模式开关(completed), 实现主题切换(completed), 运行测试(in_progress)]
7. bash → 运行测试
8. TodoWrite → [添加深色模式开关(completed), 实现主题切换(completed), 运行测试(completed)]
```

每一步都通过 TodoWrite 显式更新状态，进度清晰可见。

## 适用场景

### 适合使用

- 复杂的多步骤任务（3 步以上）
- 用户提供了多个任务（编号或逗号分隔）
- 用户明确要求使用任务列表

### 不适合使用

- 单一简单任务、信息性问答
- 3 步以内的简单操作

LLM 会根据 `@Tool` 注解中的使用场景描述自行判断是否调用 TodoWrite，开发者无需干预。

## 完整示例

参考 `spring-ai-agentx-samples` 模块中的 `TodoWriteTest`：

| 测试 | 说明 |
|------|------|
| 测试 1 | 流式 — 多步骤任务 + TodoProgress 事件 |
| 测试 2 | 非流式 — callForResult + TodoWrite |
| 测试 3 | 纯 TodoWrite — 仅任务列表管理，无其他工具 |

## 相关类

| 类 | 包路径 | 说明 |
|----|--------|------|
| `TodoWriteTool` | `com.agentx.ai.core.tools` | 任务列表工具，包含 TodoItem 和 Status 数据模型 |
| `AgentStreamEvent.TodoProgress` | `com.agentx.ai.core.model` | 流式进度事件，携带当前任务列表 |
| `ToolCallExecutor` | `com.agentx.ai.core.agent.internal` | 自动解析 TodoWrite 参数并发射进度事件 |
