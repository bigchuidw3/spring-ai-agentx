# Hook 生命周期机制

v1_1 新增 Hook 机制，提供 Agent 生命周期各阶段的观察与干预能力。Hook 是 Agent 执行引擎与外部扩展之间的桥梁，替换了 v1.0.0-M2 的 StageOutputProvider 分阶段输出。

## 1. Hook 与 Stream 事件的区别

框架有两套事件模型，职责不同：

| 事件类型 | 方向 | 用途 |
|----------|------|------|
| **HookEvent**（7 个） | 框架 → 扩展 | Hook 拦截 Agent 生命周期：改输入、改参数、观测结果、注入流事件 |
| **AgentStreamEvent**（6 个） | Agent → 客户端 | 下游流式协议：Thinking、Text、ToolStart、ToolEnd、Error、Complete |

Hook 调用是**同步的**，发生在 AgentLoopExecutor 的关键节点。AgentStreamEvent 走 Reactor sink 管道，是**异步流式的**。

通过 `AgentRuntimeContext.emitter`，Hook 可以在任何时机往流里注入 AgentStreamEvent（如注入额外的 Thinking、Text）。

## 2. AgentHook 接口

```java
public interface AgentHook {
    HookEvent onEvent(HookEvent event);

    default int priority() {
        return 0;
    }
}
```

实现者通过模式匹配处理感兴趣的事件。`priority()` 数值越大越先执行（默认 0）。

## 3. 7 个 Hook 事件

### 概览

| # | 事件 | 触发时机 | 事件字段可改 | runtimeContext 可改 |
|---|------|----------|-------------|---------------------|
| 1 | BeforeCall | 调用开始 | — | messages、emitter |
| 2 | BeforeReasoning | 每轮 LLM 调用前 | — | messages、emitter |
| 3 | AfterReasoning | 每轮 LLM 完成后 | 全部 final | messages、emitter |
| 4 | BeforeToolExecution | 每个工具执行前 | arguments、toolContext | messages、emitter |
| 5 | AfterToolExecution | 每个工具执行后 | 全部 final | messages、emitter |
| 6 | AfterCall | 调用结束（持久化后） | 全部 final | messages、emitter |
| 7 | Error | LLM 调用异常 | 全部 final | messages、emitter |

### 3.1 BeforeCallEvent

调用开始时触发，在首次推理之前。

```java
// 读取
event.getRuntimeContext().getQuery()        // 用户问题
event.getRuntimeContext().getUserId()       // 用户标识
event.getRuntimeContext().getMessages()     // 初始消息列表（可改）
```

### 3.2 BeforeReasoningEvent

每轮 LLM 推理前触发（上下文压缩已完成）。

```java
// 读取
event.getRound()                            // 当前轮次
event.getRuntimeContext().getMessages()     // 工作消息列表（可改）
```

典型用途：上下文压缩 Hook（ContextCompactionHook 即基于此事件实现）。

### 3.3 AfterReasoningEvent

每轮 LLM 推理完成后触发。

| 字段 | 类型 | 说明 |
|------|------|------|
| text | String | 本轮 LLM 输出文本 |
| toolCalls | List | 本轮工具调用决策（可能为空） |
| round | long | 当前轮次 |
| promptTokens | long | 本轮输入 token |
| completionTokens | long | 本轮输出 token |
| durationMs | long | 本轮 LLM 调用耗时 |

适合做：token 统计、本轮日志、通过 emitter 注入流的 token 信息。

### 3.4 BeforeToolExecutionEvent

每个工具执行前触发，**唯一可直接修改事件字段的 After 前事件**。

```java
// 读取
event.getToolName()         // 工具名
event.getArguments()        // 工具入参 JSON

// 修改
event.setArguments("...")   // 改写入参（如 URL 重写、参数脱敏）
event.setToolContext(ctx)   // 追加 ToolContext
```

改写后的 arguments 会传给工具 callback，ToolStart 流事件也携带改写后的值。

### 3.5 AfterToolExecutionEvent

单个工具执行完成后触发。

| 字段 | 类型 | 说明 |
|------|------|------|
| toolName | String | 工具名 |
| toolCallId | String | 工具调用 ID |
| arguments | String | 实际执行的入参 |
| result | String | 工具返回结果 |
| success | boolean | 是否成功 |
| durationMs | long | 工具执行耗时 |

适合做：工具调用审计、结果日志、异常告警。

### 3.6 AfterCallEvent

调用结束时触发（终态标记后、持久化后、Complete 流事件前）。

| 字段 | 类型 | 说明 |
|------|------|------|
| finalAnswer | String | 最终答案文本 |
| durationMs | long | 本次调用总耗时 |

通过 `getRuntimeContext()` 可读取累计 token、总轮数等。适合做：持久化后处理、长期记忆抽取触发、审计日志。

### 3.7 ErrorEvent

LLM 调用层面异常时触发（如 401 认证失败、网络超时、rate limit）。

注意：**工具内部抛异常不触发 ErrorEvent**，工具异常会被框架捕获并转为错误消息放入对话。

| 字段 | 类型 | 说明 |
|------|------|------|
| error | Throwable | 原始异常 |
| phase | String | 发生阶段（"reasoning" / "forceFinal"） |
| retryAttempt | int | 当前重试次数 |
| willRetry | boolean | 框架是否将自动重试 |

## 4. AgentRuntimeContext — 共享可变上下文

所有 Hook 事件都通过 `getRuntimeContext()` 暴露同一个 `AgentRuntimeContext` 实例。它是整个调用生命周期的共享状态：

| 属性 | 类型 | 说明 |
|------|------|------|
| query | String | 当前用户问题 |
| userId | String | 用户标识 |
| conversationId | String | 会话 ID |
| sessionId | long | 当前执行 session ID |
| messages | List\<Message\> | 工作消息列表（**跨轮共享，可修改**） |
| emitter | Consumer\<AgentStreamEvent\> | 流事件发射器（**任何 Hook 都可用**） |
| totalPromptTokens | long | 累计输入 token |
| totalCompletionTokens | long | 累计输出 token |
| totalRounds | int | 累计轮数 |

重要：messages 是跨轮共享的可变列表。在 Before* 中修改影响本轮，在 After* 中修改影响下一轮。

通过 emitter 注入流事件：

```java
var emitter = event.getRuntimeContext().getEmitter();
if (emitter != null) {
    emitter.accept(new AgentStreamEvent.Thinking("..."));
    emitter.accept(new AgentStreamEvent.Text("..."));
}
```

## 5. 使用方式

### 5.1 实现 AgentHook

```java
public class MyHook implements AgentHook {
    @Override
    public HookEvent onEvent(HookEvent event) {
        return switch (event) {
            case BeforeCallEvent e -> {
                System.out.println("调用开始: " + e.getRuntimeContext().getQuery());
                yield e;
            }
            case BeforeToolExecutionEvent e -> {
                // 改写工具参数
                String modified = e.getArguments().replace("原始值", "替换值");
                e.setArguments(modified);
                yield e;
            }
            case AfterCallEvent e -> {
                System.out.println("总耗时: " + e.getDurationMs() + "ms");
                yield e;
            }
            default -> event;
        };
    }

    @Override
    public int priority() {
        return 100;
    }
}
```

### 5.2 注册到 Agent

```java
ReactAgent agent = ReactAgent.builder()
        .chatModel(chatModel)
        .hooks(new MyHook())
        .build();
```

支持注册多个 Hook，按 priority 降序依次执行。

## 6. Priority 与执行顺序

Hook 按 `priority()` 降序执行。数值越大越先执行。系统内置 Hook 使用高 priority 值：

- ContextCompactionHook：`Integer.MAX_VALUE`（确保压缩在任何用户 Hook 之前完成）

多个用户 Hook 之间通过调整 priority 控制先后顺序。

## 7. 错误隔离

单个 Hook 抛异常只记录日志，不影响主流程和其他 Hook 的执行。Hook 不应该抛异常来中断 Agent 执行。

## 8. Hook 与 AgentStreamEvent 时序对照

一次完整调用的事件时序：

```
BeforeCall
  └→ [round-1] BeforeReasoning
        └→ LLM stream → Thinking chunks / Text chunks
        └→ AfterReasoning
        └→ [如果 LLM 决定调工具]
              BeforeToolExecution → ToolStart → exec → ToolEnd → AfterToolExecution
  └→ [round-2] BeforeReasoning
        └→ ...
  └→ [终态]
        markTerminal → persist → AfterCall → emit Complete
```

LLM 调用异常时：

```
scheduleRound → LLM error → ErrorEvent → 重试 or markTerminal("error")
```

## 9. 从 StageOutputProvider 迁移

v1.0.0-M2 的 `StageOutputProvider` 已移除。迁移到 Hook 机制：

| 旧方式 | 新方式 |
|--------|--------|
| `onBeforeCall()` | BeforeCallEvent + emitter 注入 |
| `onAfterReasoning()` | AfterReasoningEvent + emitter 注入 |
| `onBeforeComplete()` | AfterCallEvent + emitter 注入 |
| `onAfterCall()` | AfterCallEvent（观测） |

## 10. 当前限制

- ErrorEvent 仅覆盖 LLM 调用异常，工具内部异常不触发 ErrorEvent
- AfterReasoningEvent / AfterToolExecutionEvent 字段为 final，不可修改本轮结果
- 暂无 AgentScope 风格的 `stopAgent()` / `gotoReasoning()` 控制流能力

## 11. 验证样例

`samples` 模块 `v1_1/HookTest.java`，7 个测试方法覆盖全量 Hook 事件。
