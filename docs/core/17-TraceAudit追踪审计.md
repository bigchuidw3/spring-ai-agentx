# TraceAudit 追踪审计

TraceAudit 是框架内置的 LLM 调用审计能力，基于 `agentx_trace` 表记录每次 LLM 调用的请求、响应、token 用量和执行时长。用于：大模型调用链路追踪、问题定位、以及为模型微调提供训练数据。

## 核心设计

### 为什么需要 TraceAudit

Spring AI 的 ChatClient 没有暴露真实入参（messages、options、tools 的完整 JSON），给调用审计和问题追溯带来困难。TraceAudit 通过 `RequestLoggingAdvisor` 在 LLM 调用前手工构建 OpenAI 兼容格式的请求 JSON，结合 `TraceStore` 实现每轮调用的事后记录。

### 触发条件

必须同时满足以下条件，trace 才会入库：

| 条件 | 说明 |
|------|------|
| `dataSource` 已配置 | 框架通过 DataSource 创建 TraceStore |
| `enableTrace(true)`（默认） | 在 Builder 中启用，默认为 true |
| `conversationId` 非空 | 每条 trace 记录绑定到一个会话 |

仅配置 dataSource 或仅配置 enableTrace 都不会入库，二者必须同时满足。

## 快速开始

```java
DataSource dataSource = ...; // 你的 MySQL DataSource

ReactAgent agent = ReactAgent.builder()
        .chatModel(chatModel)
        .tools(BashTool.create())
        .dataSource(dataSource)            // 必须配置 dataSource
        .enableTrace(true)                  // 默认为 true，可省略
        .build();

RunnableParams params = RunnableParams.builder()
        .conversationId("conv_001")         // 必须提供 conversationId
        .userId("user_123")
        .build();

// 非流式调用
String result = agent.call("扫描桌面文件", params);

// 流式调用
agent.streamForResult("扫描桌面文件", params)
        .doOnNext(event -> {
            if (event instanceof AgentStreamEvent.Complete c) {
                System.out.println("Total prompt tokens: " + c.totalPromptTokens());
                System.out.println("Total completion tokens: " + c.totalCompletionTokens());
            }
        })
        .blockLast();
```

## 数据表结构

框架自动创建 `agentx_trace` 表，无需手动建表。

```sql
CREATE TABLE agentx_trace (
    id                BIGINT       NOT NULL,           -- 主键
    session_id        BIGINT       NOT NULL,           -- 会话 ID（一次对话一个）
    conversation_id   VARCHAR(100) NOT NULL,           -- 会话标识
    round             INT          NOT NULL,           -- 第几轮 LLM 调用
    input_data        LONGTEXT     DEFAULT NULL,       -- 请求 JSON（messages + options + tools）
    output_data       LONGTEXT     DEFAULT NULL,       -- 响应内容或工具调用列表
    think             TEXT         DEFAULT NULL,       -- 思考内容（reasoning_content / think tags）
    prompt_tokens     INT          DEFAULT 0,          -- 本轮 prompt tokens
    completion_tokens INT          DEFAULT 0,          -- 本轮 completion tokens
    duration_ms       BIGINT       DEFAULT 0,          -- 本轮执行时长（毫秒）
    success           TINYINT(1)   DEFAULT 1,          -- 是否成功
    error_message     TEXT         DEFAULT NULL,       -- 错误信息
    created_at        TIMESTAMP    DEFAULT NULL        -- 创建时间
)
```

### 字段说明

| 字段 | 说明 |
|------|------|
| `session_id` | 一次对话的唯一标识，由框架自动生成（IdWorker.getId()）。同一 conversationId 的多轮调用共享同一个 session_id。 |
| `conversation_id` | 业务层面的会话标识，来自 `RunnableParams.conversationId`。用于按会话查询 trace。 |
| `round` | 第几轮 LLM 调用。从 1 开始，每有一次 LLM 推理（无论有无敌具调用）就 +1。 |
| `input_data` | 完整的请求 JSON，格式为 OpenAI 兼容格式，包含 messages、model、temperature、tools 等字段。由 `RequestLoggingAdvisor` 手工构建。 |
| `output_data` | LLM 响应内容；如有工具调用则记录工具调用列表（JSON 格式）。 |
| `think` | 思考内容。REASONING_CONTENT 模式下为 reasoning_content 字段值；THINK_TAG 模式下为 `<think>...</think>` 标签内容。 |
| `prompt_tokens` / `completion_tokens` | 本轮 LLM 调用的 token 用量。注意是**本轮**不是累计。 |
| `duration_ms` | 本轮 LLM 调用的耗时（毫秒），从发送到收到响应。 |
| `success` | 0=失败，1=成功。失败时 error_message 记录错误信息。 |

### 索引

框架自动创建两个索引：

- `idx_agentx_trace_session` on `session_id` — 按会话查询
- `idx_agentx_trace_conv` on `conversation_id` — 按会话标识查询

## Token 统计

### 流式 Complete 事件

`AgentStreamEvent.Complete` 事件在流式调用结束时发射，包含会话级别的 token 累计：

```java
agent.streamForResult(query, params)
        .doOnNext(event -> {
            if (event instanceof AgentStreamEvent.Complete c) {
                System.out.println("累计 prompt tokens: " + c.totalPromptTokens());
                System.out.println("累计 completion tokens: " + c.totalCompletionTokens());
            }
        })
        .blockLast();
```

| 字段 | 说明 |
|------|------|
| `totalPromptTokens` | 从会话开始到结束，**所有轮次** prompt tokens 的累计值 |
| `totalCompletionTokens` | 从会话开始到结束，**所有轮次** completion tokens 的累计值 |

累计值在 `AgentExecutionContext` 中维护，每轮 LLM 调用后通过 `recordTrace()` 累加到 Context，Complete 事件从 Context 读取。

### 非流式调用

非流式调用（`call` / `callForResult`）不发射 Complete 事件，token 统计只能通过查询 `agentx_trace` 表获得：

```sql
SELECT SUM(prompt_tokens) as total_prompt,
       SUM(completion_tokens) as total_completion
FROM agentx_trace
WHERE session_id = ?;
```

## 请求 JSON 格式

`RequestLoggingAdvisor` 手工构建的请求 JSON 符合 OpenAI API 格式，便于回放和审计：

```json
{
  "messages": [
    { "role": "system", "content": "你是一个有帮助的助手" },
    { "role": "user", "content": "帮我扫描桌面文件" }
  ],
  "stream": false,
  "model": "qwen-plus",
  "temperature": 0.7,
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "Bash",
        "description": "执行 bash 命令",
        "parameters": { "type": "object", "properties": {...} }
      }
    }
  ]
}
```

### 构建时机

- **同步路径**（CallAdvisor）：在 `adviseCall()` 中调用 ChatClient 之前构建 JSON，存入 `ChatClientResponse.context()`，由 `AgentLoopExecutor` 在收到响应后读取并记 trace。
- **流式路径**（StreamAdvisor）：在 `adviseStream()` 中调用之前构建 JSON，通过 `doOnNext` 存入每个 response 的 context。

## 使用示例

### 非流式多轮工具调用

```java
ReactAgent agent = ReactAgent.builder()
        .chatModel(chatModel)
        .tools(BashTool.create())
        .dataSource(dataSource)
        .maxRounds(30)
        .build();

RunnableParams params = RunnableParams.builder()
        .conversationId("conv_001")
        .userId("user_001")
        .build();

String result = agent.call("扫描本地桌面有哪些文件，并输出结果", params);
```

### 流式多轮工具调用

```java
ToolCallback[] allTools = mergeTools(
        BashTool.create(),
        FileSystemTools.create(),
        GrepTool.create(),
        new ToolCallback[]{SkillsTool.builder().addSkillsDirectory(skillsDir).build()}
);

ReactAgent agent = ReactAgent.builder()
        .chatModel(chatModel)
        .tools(allTools)
        .thinkingMode(ThinkingMode.REASONING_CONTENT)
        .dataSource(dataSource)
        .maxRounds(30)
        .build();

agent.streamForResult(query, params)
        .doOnNext(event -> {
            if (event instanceof AgentStreamEvent.Complete c) {
                System.out.println("会话完成，prompt=" + c.totalPromptTokens()
                        + ", completion=" + c.totalCompletionTokens());
            }
        })
        .blockLast();
```

## 精细控制：enableTrace

Builder 提供 `enableTrace` 开关，默认为 `true`，用于单独控制 agentx_trace 是否记录调用审计。

```java
ReactAgent agent = ReactAgent.builder()
        .chatModel(chatModel)
        .dataSource(dataSource)
        .enableTrace(false)   // 禁用 trace 入库（默认 true）
        .build();
```

### 运行时检查机制

设计原则：`build()` 始终创建 TraceStore 对象（轻量级、幂等），`enableTrace` 仅在**运行时入库点**生效。具体地，`AgentLoopExecutor.initTrace()` 在每次调用开始时检查：

```java
if (traceStore == null || !enableTrace) return;  // 跳过 trace 记录
```

这样允许在不重建 Agent 的前提下灵活切换 trace 行为。`enableTrace` 与 `enableSession`、`enableProfileMemory` 三个开关互相独立，可自由组合：

| 开关 | 控制范围 | 详细文档 |
|------|---------|---------|
| `enableTrace` | agentx_trace（调用审计） | 本文档 |
| `enableSession` | agentx_session（短期记忆） | [分层记忆体系](05-分层记忆体系.md#精细控制开关) |
| `enableProfileMemory` | agentx_memory（用户画像） | [分层记忆体系](05-分层记忆体系.md#精细控制开关) |

> 注意：`enableTrace` 仅对主 Agent 生效。子 Agent 的 trace 行为自动跟随父 Agent（父开则子开，父关则子关），由框架在内部注入父 TraceStore 实现。

## 典型查询

### 查看某会话的完整调用链

```sql
SELECT round, input_data, output_data, think,
       prompt_tokens, completion_tokens, duration_ms, success
FROM agentx_trace
WHERE conversation_id = 'conv_001'
ORDER BY round;
```

### 统计某会话的 Token 总量

```sql
SELECT session_id,
       SUM(prompt_tokens) AS total_prompt,
       SUM(completion_tokens) AS total_completion,
       SUM(duration_ms) AS total_duration_ms
FROM agentx_trace
WHERE conversation_id = 'conv_001'
GROUP BY session_id;
```

### 定位失败调用

```sql
SELECT conversation_id, round, error_message, created_at
FROM agentx_trace
WHERE success = 0
ORDER BY created_at DESC;
```

## 相关类

| 类 | 包路径 | 说明 |
|----|--------|------|
| `RequestLoggingAdvisor` | `com.agentx.ai.core.advisors` | 请求日志拦截器，构造真实入参 JSON |
| `TraceStore` | `com.agentx.ai.core.trace` | trace 存储层，负责建表和写入 |
| `TraceManager` | `com.agentx.ai.core.trace` | 每会话一个实例，持有 sessionId + conversationId |
| `AgentStreamEvent.Complete` | `com.agentx.ai.core.model` | 流式结束事件，含 token 累计 |
| `DataSourceStorageFactory` | `com.agentx.ai.core.memory.store` | 工厂方法，创建 TraceStore |