# Spring AI AgentX

基于原生 Spring AI 的智能体（Agent）开发框架，提供 ReAct 执行引擎、分层记忆、工具调度、Human-in-the-Loop 等核心能力，形成完整的 Harness Engineering 方案，帮助开发者快速构建 AI Agent，可以快速搭建 Java 版的 Claude Code。

> 当前版本：1.0.0-M1 | JDK 21+ | Spring Boot 3.5.x | Spring AI 1.1.0

## Spring AI AgentX 是什么

![img.png](img.png)

Spring AI AgentX 是一款面向 Java 开发者的 AI Agent 开发框架。基于 **Spring AI** 和 **Reactor** 构建，专注于 Agent 执行引擎，不引入 Graph 编排范式，以简洁的方式帮助开发者构建 AI Agent。

### 设计理念

**不造轮子，只做 Agent 引擎。** 框架基于 Spring AI 原生机制（ChatClient、ToolCallback、ChatMemory）构建，不引入额外的抽象层。开发者无需学习新的编程范式，只要会用 Spring AI，就会用 AgentX。

**不用 Graph，基于 Reactor 实现。** 不引入 LangGraph 等 Graph 编排框架复杂的节点和边定义，执行引擎基于 Reactor 构建，以声明式的响应式流驱动 Agent 的多轮推理与工具调用。从流式输出到工具执行全链路响应式，天然适配 WebFlux 等异步场景。

**Harness Engineering。** 借鉴 Claude Code 的架构理念，大模型是引擎，框架是底盘（Harness）。框架不限定模型选择，但模型以外的一切 —— 工具调度、分层记忆、执行控制、Human-in-the-Loop、上下文管理、技能体系 —— 全部由框架统一提供，让开发者只需关注业务逻辑和模型选型。

## 写在前面

这个框架源于我近三年在 Agent 产品领域的持续深耕。最初只是出于好奇 —— 不论是 Python 的 LangChain、LangGraph、AutoGen……还是 Java 的 Spring AI、Spring AI Alibaba、AgentScope，就想搞清楚这些框架底层到底在做什么，不只是知其然，更想知其所以然。

后来在公司主导 Agent 产品开发的过程中，踩了不少坑，也遇到了开源框架的一些局限性。与其在各种框架之间反复适配修补，不如把积累的经验沉淀成一套自己的框架。AgentX 的每个功能模块都源自真实业务场景，是反复打磨的成果。框架完全基于原生 Spring AI，只用基础能力来实现 Agent 的各种功能。

最初的代码根基，也就是 ReAct 主循环，完全是我手搓实现。随着 vibe coding 的发展、大模型能力的增强，现在框架中大部分代码在我的架构设计和思路引导下由 AI 辅助完成，但每个模块的设计决策、技术选型和边界把控，都来自实战中的思考。

希望这个框架能让大家对 Agent 开发有更深层次的理解。

## 功能概览

### v1.0.0-M1（当前版本）

| 功能 | 说明 |
|------|------|
| ReAct Agent 引擎 | 基于 Reasoning + Acting 范式构建多轮执行闭环，自动驱动模型推理与工具调用 |
| 同步与流式输出 | 同步调用（call）与流式输出（stream）两种模式，流式基于 Reactor Flux 实现 |
| 统一工具调度 | 原生支持 Function Calling / MCP，工具执行由框架统一接管 |
| 运行时参数注入 | 通过 RunnableParams 动态覆盖工具参数，精准控制工具行为 |
| 任务管理与执行控制 | 会话级并发控制与中断机制，保障同一会话执行的有序性与可控性 |
| 分层记忆体系 | 短期记忆、用户画像、长期记忆（RAG）三层结构，多粒度上下文管理 |
| 分阶段输出 | StageOutputProvider SPI，在 Agent 生命周期钩子点注入自定义输出 |
| Human-in-the-Loop | 暂停/恢复机制，支持输入工具与操作工具，支持自定义用户输入工具 |
| 内置工具能力集 | Bash、文件系统、文本检索（Grep）、Python 等通用工具 |
| Skills 技能体系 | 基于渐进式披露机制，按需加载技能内容，支持多技能注册与组合 |
| 思考模型适配 | 新增 `ThinkingMode` 枚举统一 `THINK_TAG` / `REASONING_CONTENT` / `DISABLED` 三种模式，支持 DeepSeek、Qwen3.6-plus 等模型的 `reasoning_content` 字段解析 |
| DeepSeek-V4 兼容 | 内置 `DeepSeekV4ChatModel`，解决 Spring AI 原生模块在思考模式 + 工具调用场景下的兼容性问题（[spring-ai#6026](https://github.com/spring-projects/spring-ai/issues/6026)） |
| 异常处理与重试 | 内置透明重试机制，统一异常处理（AgentException + AgentErrorCode） |
| 上下文压缩 | 两层自动压缩策略（micro_compact + auto_compact），控制长对话 Token 消耗 |
| ToolSearch 工具检索 | 工具按需发现，LLM 通过 tool_search 元工具搜索加载 deferred 工具 |
| 结构化输出 | 非流式调用输出标准 JSON，支持单对象和泛型集合 |

## 快速开始

### 1. 构建安装

当前版本需从源码构建安装到本地 Maven 仓库：

```bash
git clone https://github.com/bigchuidw3/spring-ai-agentx.git
cd spring-ai-agentx
mvn clean install -DskipTests
```

后续会考虑发布到 Maven Central，届时可直接通过坐标引入，无需手动构建。

### 2. 引入依赖

在项目 `pom.xml` 中引入：

```xml
<dependency>
    <groupId>com.agentx.ai</groupId>
    <artifactId>spring-ai-agentx-core</artifactId>
    <version>1.0.0-M1</version>
</dependency>
```

框架基于 Spring AI 1.1.0，core 模块已内置 `spring-ai-starter-model-openai`，无需额外引入。

### 3. 构造 ChatModel

框架本身不绑定特定大模型，任何 Spring AI 支持的 ChatModel 都可以直接传入。

**方式 A：兼容 OpenAI 接口的模型（Qwen / DeepSeek / Moonshot 等）**

```java
ChatModel chatModel = OpenAiChatModel.builder()
        .openAiApi(OpenAiApi.builder()
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/")  // 替换为对应厂商的 base URL
                .apiKey("your-api-key")
                .build())
        .defaultOptions(OpenAiChatOptions.builder()
                .model("qwen-plus")
                .temperature(0.7)
                .build())
        .build();
```

推荐模型 base URL 参考：

| 厂商 | base URL | 推荐模型 | 类型 |
|------|----------|---------|------|
| 通义千问 (DashScope) | `https://dashscope.aliyuncs.com/compatible-mode/` | qwen-plus | 普通模型 |
| 智谱 AI (GLM) | 需引入 `spring-ai-starter-model-zhipuai` | glm-4.7 / glm-5.1 | 普通模型 |
| DeepSeek | `https://api.deepseek.com/` | deepseek-v4-flash | reasoning_content 模型（需使用框架内置 `DeepSeekV4ChatModel`，详见 [15-DeepSeek-V4兼容](docs/core/15-DeepSeek-V4兼容.md)） |
| MiniMax | `https://api.minimaxi.com/` | minimax-M2.7 | Think 模型 |

> **模型选择建议**：一般场景推荐 qwen-plus；需要 Agent 展示思考过程时，根据模型类型选择对应的 `ThinkingMode`：
> - `<think/>` 标签格式（MiniMax 等）→ `thinkingMode(ThinkingMode.THINK_TAG)`
> - `reasoning_content` 字段格式（DeepSeek、Qwen3.6-plus）→ `thinkingMode(ThinkingMode.REASONING_CONTENT)`
>
> 详见 [09-思考模型适配](docs/core/09-思考模型适配.md)。

> **关于模型兼容性**：虽然各家模型厂商都声称兼容 OpenAI 接口，但实际在请求参数和响应格式上往往存在差异（如思考模式入参出参的差异、字段校验）。框架会优先适配主流模型（如上表所列），但无法保证当前框架兼容市面上所有模型，希望各位理解。

**方式 B：智谱 AI（GLM 系列，需额外引入 `spring-ai-starter-model-zhipuai`）**

```java
ChatModel chatModel = new ZhiPuAiChatModel(
        ZhiPuAiApi.builder().apiKey("your-api-key").build(),
        ZhiPuAiChatOptions.builder()
                .model("glm-4.7")
                .temperature(0.7)
                .build()
);
```

**方式 C：DeepSeek V4 思考模型（必须使用框架内置 `DeepSeekV4ChatModel`）**

```java
ChatModel chatModel = DeepSeekV4ChatModel.builder()
        .deepSeekApi(DeepSeekApi.builder()
                .apiKey("your-api-key")
                .baseUrl("https://api.deepseek.com")
                .build())
        .defaultOptions(DeepSeekChatOptions.builder()
                .model("deepseek-v4-flash")
                .temperature(0.4)
                .build())
        .build();
```

> **注意**：DeepSeek V4 等思考模型存在 `reasoning_content` 回传兼容性问题，不能使用 OpenAI 兼容接口或 Spring AI 原生模块。详见 [15-DeepSeek-V4兼容](docs/core/15-DeepSeek-V4兼容.md)。

**方式 D：Spring Boot 自动注入**

```yaml
# application.yml
spring:
  ai:
    openai:
      base-url: https://dashscope.aliyuncs.com/compatible-mode/
      api-key: your-api-key
      chat:
        options:
          model: qwen-plus
          temperature: 0.7
```

```java
@Autowired
ChatModel chatModel;  // Spring Boot 自动注入，直接传给 Agent
```

### 4. 最简单的 Agent

3 行代码构建一个可用的智能体：

```java
ReactAgent agent = ReactAgent.builder()
        .chatModel(chatModel)
        .build();

String answer = agent.call("帮我分析一下 Java 和 Go 的优劣势");
```

流式输出：

```java
agent.stream("用三句话介绍 Spring AI")
        .doOnNext(chunk -> System.out.print(chunk))
        .blockLast();
```

### 5. 自定义系统提示词

通过 `instructions` 设置 Agent 的角色和行为规则：

```java
ReactAgent agent = ReactAgent.builder()
        .chatModel(chatModel)
        .instructions("""
                你是一个 Java 架构师助手。
                遵循以下原则：
                - 给出可落地的方案，不写空话
                - 优先使用成熟框架和最佳实践
                """)
        .build();

String answer = agent.call("帮我设计一个订单系统的架构");
```

---

## 功能文档

> 详细的功能说明和使用示例请查看 [docs/core/](docs/core/) 目录下的文档。

| # | 功能 | 说明 | 文档 |
|---|------|------|------|
| 1 | 同步与流式输出 | call/stream 双模式，基础 API 和高级 API | [01-同步与流式输出](docs/core/01-同步与流式输出.md) |
| 2 | 工具与 MCP | 内置工具、自定义工具、MCP 协议支持 | [02-工具与MCP](docs/core/02-工具与MCP.md) |
| 3 | 动态会话参数 | RunnableParams、addParam / addToolParam 参数注入 | [03-动态会话参数](docs/core/03-动态会话参数.md) |
| 4 | 任务管理与并发控制 | 会话级并发控制、外部中断 | [04-任务管理与并发控制](docs/core/04-任务管理与并发控制.md) |
| 5 | 分层记忆体系 | 短期记忆、用户画像、长期记忆三层架构 | [05-分层记忆体系](docs/core/05-分层记忆体系.md) |
| 6 | 分阶段输出 | StageOutputProvider SPI，三阶段钩子 | [06-分阶段输出](docs/core/06-分阶段输出.md) |
| 7 | Human-in-the-Loop | 暂停/恢复机制，用户输入工具与操作审批 | [07-Human-in-the-Loop](docs/core/07-Human-in-the-Loop.md) |
| 8 | Skills 技能体系 | 渐进式披露，按需加载技能 | [08-Skills技能体系](docs/core/08-Skills技能体系.md) |
| 9 | 思考模型适配 | 支持 `<think/>` 标签和 `reasoning_content` 两种思考输出格式 | [09-思考模型适配](docs/core/09-思考模型适配.md) |
| 10 | 异常处理与重试 | 透明重试机制，统一异常处理 | [10-异常处理与重试](docs/core/10-异常处理与重试.md) |
| 11 | 上下文压缩 | 两层自动压缩策略，控制 Token 消耗 | [11-上下文压缩](docs/core/11-上下文压缩.md) |
| 12 | ToolSearch 工具检索 | 工具按需发现，keyword + LLM 双模式搜索 | [12-ToolSearch工具检索](docs/core/12-ToolSearch工具检索.md) |
| 13 | 结构化输出 | 非流式调用输出标准 JSON，支持单对象和泛型集合 | [13-结构化输出](docs/core/13-结构化输出.md) |
| 14 | 综合示例 | Skills + HITL + 分阶段输出完整示例 | [14-综合示例](docs/core/14-综合示例.md) |
| 15 | DeepSeek-V4 兼容 | reasoning_content 回传兼容性修复与使用指南 | [15-DeepSeek-V4兼容](docs/core/15-DeepSeek-V4兼容.md) |

---

## 示例参考（Samples）

项目提供了完整的示例代码，位于 `spring-ai-agentx-samples` 模块，可直接运行体验各功能。

### 配置

示例需要真实 API Key 和数据库连接。首次运行前复制配置模板并填入真实值：

```bash
cd spring-ai-agentx-samples/src/main/resources
cp secrets.properties.example secrets.properties
# 编辑 secrets.properties，填入真实配置
```

- `secrets.properties.example` — 模板文件（仅占位符），提交到 Git
- `secrets.properties` — 真实配置，已在 `.gitignore` 中排除，不提交

### 示例列表

| 示例类 | 涵盖功能 | 说明 |
|--------|---------|------|
| `RunnableParamsTest` | 运行时参数注入 | addParam / addToolParam 参数覆盖 |
| `AgentSkillsTest` | Skills 技能体系 | 技能加载与多技能组合 |
| `TaskManagerTest` | 任务管理与并发控制 | 会话级并发控制与中断 |
| `RetryErrorTest` | 异常处理与重试 | 重试机制与错误码处理 |
| `HumanInTheLoopTest` | Human-in-the-Loop | 暂停/恢复、ask_user、操作审批 |
| `StageOutputTest` | 分阶段输出 | StageOutputProvider、多时机钩子、Think 标签 |
| `ThinkingModeTest` | 思考模型适配 | REASONING_CONTENT / THINK_TAG / DISABLED 三种模式 |
| `MemoryTest` | 分层记忆体系 | 短期记忆、用户画像、长期记忆 |
| `ContextManagementTest` | 上下文压缩 | 默认配置、自定义配置、保护工具 |
| `FullIntegrationTest` | 完整集成 | 三层记忆 + Skills + 全量工具 |
| `ToolSearchTest` | ToolSearch 工具检索 | 同步/流式调用、Session 隔离验证 |
| `StructuredOutputTest` | 结构化输出 | call/callForResult 单对象与集合输出 |

> 各示例内部通过 `testNumber` 切换测试场景，修改后直接运行 `main` 方法即可。

---

## 版本发布

| 版本 | 状态 | 说明 |
|------|------|------|
| [v1.0.0-M1](#v100-m1当前版本) | 当前版本 | 首个里程碑版本，ReAct 引擎 + 分层记忆 + 工具调度 + 思考模型适配 + DeepSeek-V4 兼容等核心能力 |

## 版本路线图

### v1.0.0-M1（当前版本）

- [x] ReAct Agent 引擎 — 基于 Reasoning + Acting 范式的多轮执行闭环
- [x] 同步调用与流式输出 — call / stream 双模式，流式基于 Reactor Flux
- [x] 统一工具调度 — 原生支持 Function Calling / MCP，框架统一接管执行
- [x] 运行时参数注入 — RunnableParams 动态覆盖工具参数
- [x] 任务管理与执行控制 — 会话级并发控制与中断机制
- [x] 分层记忆体系 — 短期记忆、用户画像、长期记忆（RAG）
- [x] Human-in-the-Loop — Agent 主动提问与操作审批，暂停/恢复执行
- [x] 阶段式输出 — StageOutputProvider SPI，三种钩子时机
- [x] 内置工具能力集 — Bash、文件系统、Grep、Python
- [x] Skills 技能体系 — 渐进式披露，按需加载
- [x] 思考模型适配 — `ThinkingMode` 枚举统一 THINK_TAG / REASONING_CONTENT / DISABLED 三种模式
- [x] DeepSeek-V4 兼容 — 内置 `DeepSeekV4ChatModel`，解决思考模式 + 工具调用兼容性问题
- [x] 异常处理与重试 — 内置透明重试机制，AgentException + AgentErrorCode
- [x] 上下文压缩 — 两层自动压缩策略，控制 Token 消耗
- [x] ToolSearch 工具检索 — 工具按需发现，keyword + LLM 双模式搜索
- [x] 结构化输出 — 非流式调用输出标准 JSON，支持单对象和泛型集合

### v1.0.0-M2（规划中）

- [ ] todowrite — 复杂的多步骤任务，保证智能体不会遗漏任何步骤
- [ ] SubAgent 机制（Agent Tools） — 将 Agent 封装为另一个 Agent 的工具
- [ ] 后置任务校验机制 — 任务完成后引入模型校验或规则校验

### v1.0.0-M3（规划中）

- [ ] 执行沙箱 — 为工具调用与代码执行提供隔离运行环境
- [ ] Plan & Execute 架构 — 在 ReAct 基础上引入显式规划阶段
- [ ] Agent Teams — 多 Agent 按角色协同

### v1.0.0-GA（远期规划）

- [ ] 可观测性体系 — 全链路追踪，支持接入 Langfuse 等观测系统
- [ ] 外部记忆系统对接 — 支持 mem0 等外部长期记忆系统

## License

This project is licensed under the [Apache License 2.0](LICENSE).
