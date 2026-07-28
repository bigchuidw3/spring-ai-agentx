# Spring AI AgentX

基于原生 Spring AI 的智能体（Agent）开发框架，提供 ReAct 执行引擎、当前会话记忆、长期记忆、上下文压缩、工具调度、Human-in-the-Loop 等核心能力，帮助开发者快速构建可落地的 Java Agent。

> 当前发布版本：1.0.1
>
> 历史版本：1.0.0-M2
>
> JDK 21+ | Spring Boot 3.5.x | Spring AI 1.1.0

## Spring AI AgentX 是什么

Spring AI AgentX 是一款面向 Java 开发者的 AI Agent 开发框架。框架基于 Spring AI 与 Reactor 构建，专注 Agent 执行引擎本身，不引入额外的 Graph 编排范式，尽量复用 Spring AI 原生能力完成多轮推理、工具调用、会话持久化与执行控制。

### 设计理念

- 不造新范式，只做 Agent 引擎
- 不用 Graph，基于 Reactor 驱动多轮执行
- 把模型之外的能力统一收口到 Harness：工具调度、当前会话状态、上下文压缩、HITL、追踪审计、技能体系

## 当前能力总览

| 能力 | 说明 |
|------|------|
| ReAct Agent 引擎 | 基于 Reasoning + Acting 驱动多轮执行闭环，统一处理 LLM 调用、工具调用与终态收敛 |
| 同步与流式输出 | `call` / `stream` 双模式，流式基于 Reactor Flux，支持流式中途停止 |
| 当前会话记忆 | `agentx_conversation` 记录每次调用边界；`agentx_session` 按 `original_messages`、`working_messages`、`offload_context` 三种状态键维护会话状态 |
| 长期记忆 | 从 `original_messages` 异步抽取跨会话知识，写入外部 VectorStore；每次调用前按 `userId` 语义检索注入 system prompt |
| 上下文压缩 | 参考 AgentScope 的 6 层渐进式压缩思路，并在其之上做了优化（详见下文与 [11-上下文压缩](docs/core/v1_1/11-上下文压缩.md)） |
| 结构化输出 | `RunnableParams.outputType(...)` 按单次调用启用 JSON 输出，不影响同一会话中的普通对话 |
| 工具调度与 MCP | 原生 Function Calling + MCP 协议，工具执行由框架统一接管；支持 ToolSearch 按需发现 |
| 运行时参数注入 | `RunnableParams` 动态覆盖工具参数，精准控制工具行为 |
| 任务管理与并发控制 | 会话级并发控制与中断机制，保障同一会话执行的有序性与可控性 |
| Human-in-the-Loop | `askUser(true)` 默认注册内置 `ask_user` 工具与对应暂停拦截；支持审批类工具与输入类工具两种语义 |
| 中断与恢复 | `agentx_pause_state` 持久化暂停快照，统一支持 `HITL_TOOL_REQUEST` 与 `USER_INTERRUPT` 两种暂停原因 |
| SubAgent 子代理 | 子代理以 `call_{name}` 工具形式委派，拥有独立 context window；父 Agent 是 session 持久化边界 |
| TraceAudit 追踪审计 | `agentx_trace` 记录每轮 LLM 请求、响应、思考内容与 token 消耗 |
| TodoWrite 任务追踪 | 结构化任务列表工具，支持流式 TodoProgress 事件 |
| Skills 技能体系 | 按需加载技能内容，减少大段提示词常驻上下文 |
| 思考模型适配 | 支持 `<think/>` 标签和 `reasoning_content` 两种思考输出格式，内置 `DeepSeekV4ChatModel` 兼容修复 |
| 异常处理与重试 | 内置透明重试机制，统一异常处理（AgentException + AgentErrorCode） |
| 分阶段输出 | StageOutputProvider SPI，在 Agent 生命周期钩子点注入自定义输出 |

## v1.0.1 相对 v1.0.0-M2 做了哪些调整

v1.0.1 重点是把会话存储模型、上下文压缩、HITL、暂停恢复这一层重新对齐到一套自洽的执行模型上。下面是架构级调整项：

| 模块 | v1.0.0-M2 口径                         | v1.0.1 调整 |
|------|--------------------------------------|-------------|
| 会话存储模型 | 单一历史表（`messages` 字段）                 | `agentx_session` 按 `original_messages` / `working_messages` / `offload_context` 三态组织；新增 `agentx_conversation` 表，每次调用一行，记录调用边界 |
| `SystemMessage` 持久化 | 历史里会带 system                         | 统一不进入 `agentx_session`，由运行时重新注入 |
| 上下文压缩 | 两层自动压缩（micro_compact + auto_compact） | 6 层渐进式压缩策略链 + 外层门禁 + `lastKeep` 保护区 + `context_reload` 工具回溯（详见下文） |
| HITL | 工具统一按审批工具处理                          | 区分审批类工具（用户确认后才执行）与输入类工具（用户回答即工具结果）；`askUser(true)` 默认注册内置 `ask_user` 工具与输入型拦截 |
| 暂停恢复 | 单表语义                                 | `agentx_pause_state` 管恢复快照，`agentx_session` 管当前会话状态，两张表分工明确 |
| 长期记忆 | 跨会话长期记忆                              | VectorStore 由调用方构造；LLM 抽取 → 去重合并 → 跨会话注入 |
| SubAgent | 子代理行为不显式约束                           | 显式约束：禁止嵌套 SubAgent、禁止 AskUser、禁止 PauseAdvisor；父 Agent 才是 session 持久化边界 |

详细差异说明见 [docs/core/v1_1](docs/core/v1_1) 下的专题文档。

## 关于上下文压缩相对 AgentScope 的优化

AgentScope（ASJ）本身就提供了 6 层渐进式上下文压缩思路，框架在这套思路之上做了几点关键优化。这里只做简要说明，详细差异请看 [11-上下文压缩](docs/core/v1_1/11-上下文压缩.md)。

### 1. L1 抛弃 LLM 调用，改为规则替换

AgentScope 的 L1（历史工具调用压缩）即使命中也要调一次 LLM 生成摘要，对于“连续工具消息”这种结构化极强的内容，开销和延迟都不划算。

v1.0.1 的 L1 改为：

- 用字符串模板把连续工具消息替换成结构化清单
- 原文按段整体 offload，保留 uuid 链路
- 完全不调 LLM，毫秒级完成

### 2. LLM 调用边界重新归类

把 6 层策略按是否调 LLM 重新归类，调用方一眼能看清开销：

| 层级 | 是否调 LLM | 处理区 |
|------|------------|--------|
| L1 历史工具调用列表 | 否 | 历史区 |
| L2 历史大消息 offload（保留 `lastKeep`） | 否 | 历史区 |
| L3 历史大消息 offload（不保留 `lastKeep`） | 否 | 历史区 |
| L4 历史轮次摘要 | 是 | 历史区 |
| L5 当前轮大消息摘要 | 是 | 当前任务区 |
| L6 当前轮整体压缩 | 是 | 当前任务区 |

也就是 **L1-L3 不调 LLM，L4-L6 调 LLM**；**L1-L4 处理历史区，L5-L6 处理当前任务区**。

### 3. 新增 `agentx_conversation` 表，调用边界不再需要自己算

AgentScope 只通过 sessions 持久化，调用方很难直接知道“哪几条 session 属于同一次调用”，得自己根据时间戳或会话状态去拼。

v1.0.1 把调用边界独立成一张 `agentx_conversation` 表：

- 每次 `call` / `stream` 一开局就写一行
- 终态时把状态、token 用量、最终回答回写到同一行
- `agentx_session` 只负责会话消息状态

这样调用方查“我这一次调用到底发生了什么”非常直接，不用再做聚合。

## 快速开始

### 1. 从源码构建

```bash
git clone https://github.com/bigchuidw3/spring-ai-agentx.git
cd spring-ai-agentx
mvn clean install -DskipTests
```

### 2. 引入依赖

```xml
<dependency>
    <groupId>com.agentx.ai</groupId>
    <artifactId>spring-ai-agentx-core</artifactId>
    <version>1.0.1</version>
</dependency>
```

### 3. 构建一个最小可用 Agent

```java
ChatModel chatModel = OpenAiChatModel.builder()
        .openAiApi(OpenAiApi.builder()
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/")
                .apiKey("your-api-key")
                .build())
        .defaultOptions(OpenAiChatOptions.builder()
                .model("qwen-plus")
                .temperature(0.7)
                .build())
        .build();

DataSource dataSource = ...;
JdbcPauseStateStore stateStore = new JdbcPauseStateStore(dataSource);

ReactAgent agent = ReactAgent.builder()
        .chatModel(chatModel)
        .dataSource(dataSource)
        .stateStore(stateStore)
        .contextPolicy(ContextPolicy.defaults())
        .askUser(true)
        .maxRounds(10)
        .build();

RunnableParams params = RunnableParams.builder()
        .conversationId("conv_001")
        .userId("user_123")
        .build();

AgentResult result = agent.callForResult("帮我查一下北京天气，并给我一句穿衣建议", params);
```

### 4. 数据持久化与开关

传入 `DataSource` 后，框架会按需初始化会话表和追踪表。常用控制项如下：

| 参数 | 控制内容 | 默认值 | 说明 |
|------|----------|--------|------|
| `enableSession` | `agentx_session` 当前会话状态 | `true` | SubAgent 场景会被框架自动关闭 |
| `enableTrace` | `agentx_trace` 审计日志 | `true` | 父 Agent 关闭时，SubAgent 也不再记录 trace |

## v1.0.1 文档导航（v1_1）

下列专题在 v1.0.1 中做了调整，对应文档放在 [docs/core/v1_1](docs/core/v1_1) 下：

| 文档 | 说明 |
|------|------|
| [05-分层记忆体系](docs/core/v1_1/05-分层记忆体系.md) | 当前会话记忆三态、长期记忆的抽取/合并/注入链路、`LongTermMemoryConfig` 配置 |
| [11-上下文压缩](docs/core/v1_1/11-上下文压缩.md) | 6 层策略链、LLM 调用边界、历史区/当前任务区划分、AgentScope 优化点 |
| [13-结构化输出](docs/core/v1_1/13-结构化输出.md) | `outputType` 的 per-call 语义，以及它与会话持久化的关系 |
| [18-SubAgent子代理](docs/core/v1_1/18-SubAgent子代理.md) | `call_{name}` 委派模型、父子会话边界和当前限制 |
| [19-中断与恢复](docs/core/v1_1/19-中断与恢复.md) | PauseState 持久化、HITL/Interrupt 两种暂停语义与恢复规则 |

推荐阅读顺序：05 → 11 → 13 → 19 → 18。

## 其他专题文档（沿用 v1.0.0-M2）

下列专题在 v1.0.1 中未做架构级调整，仍然沿用 v1.0.0-M2 的口径，文档放在 [docs/core/v1_M2](docs/core/v1_0_M2) 下：

| # | 功能 | 文档 |
|---|------|------|
| 1 | 同步与流式输出 | [01-同步与流式输出](docs/core/v1_0_M2/01-同步与流式输出.md) |
| 2 | 工具与 MCP | [02-工具与MCP](docs/core/v1_0_M2/02-工具与MCP.md) |
| 3 | 动态会话参数 | [03-动态会话参数](docs/core/v1_0_M2/03-动态会话参数.md) |
| 4 | 任务管理与并发控制 | [04-任务管理与并发控制](docs/core/v1_0_M2/04-任务管理与并发控制.md) |
| 6 | 分阶段输出 | [06-分阶段输出](docs/core/v1_0_M2/06-分阶段输出.md) |
| 7 | Human-in-the-Loop | [07-Human-in-the-Loop](docs/core/v1_0_M2/07-Human-in-the-Loop.md) |
| 8 | Skills 技能体系 | [08-Skills技能体系](docs/core/v1_0_M2/08-Skills技能体系.md) |
| 9 | 思考模型适配 | [09-思考模型适配](docs/core/v1_0_M2/09-思考模型适配.md) |
| 10 | 异常处理与重试 | [10-异常处理与重试](docs/core/v1_0_M2/10-异常处理与重试.md) |
| 12 | ToolSearch 工具检索 | [12-ToolSearch工具检索](docs/core/v1_0_M2/12-ToolSearch工具检索.md) |
| 14 | 综合示例 | [14-综合示例](docs/core/v1_0_M2/14-综合示例.md) |
| 15 | DeepSeek-V4 兼容 | [15-DeepSeek-V4兼容](docs/core/v1_0_M2/15-DeepSeek-V4兼容.md) |
| 16 | TodoWrite 任务追踪 | [16-TodoWrite任务追踪](docs/core/v1_0_M2/16-TodoWrite任务追踪.md) |
| 17 | TraceAudit 追踪审计 | [17-TraceAudit追踪审计](docs/core/v1_0_M2/17-TraceAudit追踪审计.md) |

## v1.0.1 示例索引

`spring-ai-agentx-samples` 模块下 `v1_1` 包下的样例可以直接验证当前行为：

| 示例类 | 主要验证点 |
|--------|------------|
| `ToolCallSessionTest` | 工具调用后 `agentx_conversation` / `agentx_session` 的基础落库行为 |
| `MemoryTest` | 当前会话记忆不跨会话；长期记忆跨会话抽取、注入 |
| `MultiTurnConversationTest` | 同一 `conversationId` 跨多轮复用会话状态 |
| `CompressionLayerTest` | 6 层压缩策略、`working_messages` 覆盖写与 `offload_context` 留痕 |
| `StructuredOutputSessionTest` | 同一会话中先普通回答、再切换结构化输出 |
| `InterruptResumeSessionTest` | `USER_INTERRUPT` / `HITL_TOOL_REQUEST` 暂停恢复与会话落库 |
| `SubAgentSessionTest` | SubAgent 流式委派时，只有父 Agent 写 `agentx_session` |

## 版本路线图

### v1.0.1（当前发布版本）

- 当前会话记忆三态模型（`original_messages` / `working_messages` / `offload_context`）
- 新增 `agentx_conversation` 表，独立记录调用边界
- 6 层渐进式上下文压缩 + `context_reload` 回溯
- HITL 语义统一（审批类工具 / 输入类工具）
- 暂停恢复双表分工（`agentx_pause_state` + `agentx_session`）
- SubAgent 约束与父 Agent 会话边界明确
- 长期记忆：VectorStore 驱动，从 `original_messages` 异步抽取 → 去重合并 → 跨会话注入

### v1.0.0-M2（历史版本）

- TodoWrite 任务追踪
- TraceAudit 追踪审计
- SubAgent 子代理
- 中断与恢复

### v1.0.0-M1（历史版本）

- ReAct Agent 引擎
- 同步调用与流式输出
- 统一工具调度
- 运行时参数注入
- 任务管理与执行控制
- 分层记忆体系
- Human-in-the-Loop
- 分阶段输出
- 内置工具能力集
- Skills 技能体系
- 思考模型适配
- DeepSeek-V4 兼容
- 异常处理与重试
- 上下文压缩（两层）
- ToolSearch 工具检索
- 结构化输出

### 规划中

- 执行沙箱
- Plan & Execute 架构
- Agent Teams
- 可观测性体系

## License

This project is licensed under the [Apache License 2.0](LICENSE).
