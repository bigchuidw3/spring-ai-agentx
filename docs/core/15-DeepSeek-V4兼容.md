# DeepSeek-V4 兼容说明

## 背景

DeepSeek V4 等思考模型通过独立的 `reasoning_content` 字段返回推理过程。DeepSeek API 有一个严格要求：**上一轮返回的 `reasoning_content` 必须原样回传给下一轮 API**，否则返回 HTTP 400 错误：

```
The `reasoning_content` in the thinking mode must be passed back to the API.
```

这一问题不仅存在于 AgentX 框架，Spring AI 原生 `DeepSeekChatModel` 也存在同样的 bug（[spring-ai#6026](https://github.com/spring-projects/spring-ai/issues/6026)），目前大多数基于 Spring AI 的智能体框架都无法正常兼容 DeepSeek 思考模式 + 工具调用的组合场景。

## 根因

问题出在两个层面：

1. **Spring AI 原生 `DeepSeekChatModel`**：`reasoning_content` 存放在 `DeepSeekAssistantMessage` 子类的独立字段中，`createRequest()` 序列化请求时未从历史消息中提取并回传
2. **智能体框架层面**：ReAct 循环中，工具调用场景下构建 `AssistantMessage` 时丢弃了 `reasoning_content`

## AgentX 的做法

框架从两个层面解决了这个问题，且**不引入任何 DeepSeek 特定依赖**，保持框架通用性：

- **流式/非流式路径**：从 `AssistantMessage` 中提取 `reasoning_content` 并通过标准 `properties` 传递给下一轮 LLM 调用
- **提取逻辑通用化**：依次从 metadata、子类字段（反射）中提取，不硬编码具体模型类型

## 使用方式

> **重要**：DeepSeek V4 思考模型必须使用框架内置的 `DeepSeekV4ChatModel`，不能使用 Spring AI 原生的 `DeepSeekChatModel` 或通过 OpenAI 兼容接口（`OpenAiChatModel`）访问 DeepSeek。

### 1. 构造 ChatModel

使用 `com.agentx.ai.core.chatmodels.DeepSeekV4ChatModel`：

```java
import com.agentx.ai.core.chatmodels.DeepSeekV4ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;

DeepSeekApi deepSeekApi = DeepSeekApi.builder()
        .apiKey("your-api-key")
        .baseUrl("https://api.deepseek.com")
        .build();

DeepSeekChatOptions options = DeepSeekChatOptions.builder()
        .model("deepseek-v4-flash")
        .temperature(0.4)
        .build();

ChatModel chatModel = DeepSeekV4ChatModel.builder()
        .deepSeekApi(deepSeekApi)
        .defaultOptions(options)
        .build();
```

### 2. 构建 Agent

指定 `ThinkingMode.REASONING_CONTENT`：

```java
ReactAgent agent = ReactAgent.builder()
        .chatModel(chatModel)
        .thinkingMode(ThinkingMode.REASONING_CONTENT)
        .build();

// 同步调用
String answer = agent.call("帮我分析一下 Java 和 Go 的优劣势");

// 流式调用
agent.stream("用三句话介绍 Spring AI")
        .doOnNext(chunk -> System.out.print(chunk))
        .blockLast();
```

## 示例代码

完整示例见 `StageOutputTest.testDeepSeekV4ReactAgent()`（`testNumber = 8`），演示了 DeepSeek V4 + 工具调用 + 流式输出的完整流程：

```java
// StageOutputTest.java #8
ChatModel chatModel = TestConfig.createDeepSeekV4ChatModel();
ReactAgent agent = ReactAgent.builder()
        .chatModel(chatModel)
        .tools(BashTool.create(), FileSystemTools.create(), GrepTool.create())
        .thinkingMode(ThinkingMode.REASONING_CONTENT)
        .maxRounds(50)
        .build();

TestConfig.collectStreamEvents(agent.streamForResult("帮我扫描桌面有哪些文件", RunnableParams.empty()));
```

## 注意事项

- **必须使用 `DeepSeekV4ChatModel`**：不要使用 Spring AI 原生的 `DeepSeekChatModel` 或通过 OpenAI 兼容接口（`OpenAiChatModel`）访问 DeepSeek，否则工具调用时会报 `reasoning_content` 错误
- **必须设置 `ThinkingMode.REASONING_CONTENT`**：否则框架不会从流式 chunk 中提取 `reasoning_content`
- **依赖要求**：需要引入 `spring-ai-deepseek` 依赖（框架 core 模块已包含）
