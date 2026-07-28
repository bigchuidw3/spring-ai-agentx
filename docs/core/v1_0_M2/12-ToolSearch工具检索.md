# ToolSearch 工具检索

当 Agent 注册的工具数量较多时（超过 30-50 个），将所有工具定义一次性注入 LLM 会导致：

- **上下文膨胀**：每个工具的 JSON Schema 占用大量 Token，挤占可用上下文
- **选择准确率下降**：模型在大量工具中难以准确选择合适的工具
- **成本增加**：每轮 LLM 调用都携带全部工具定义，即使大部分未被使用

ToolSearch 将工具分为 **alwaysLoad**（始终可见）和 **deferred**（按需发现），LLM 在需要时通过 `tool_search` 元工具搜索并动态加载 deferred 工具。

> 不配置 `deferredTools` 时 ToolSearch 不启用，Agent 行为完全不变。

## 工作原理

```
┌─────────────────────────────────────────────────────────┐
│ ReactAgent.build()                                       │
│                                                          │
│ alwaysLoad tools → ChatClient（始终可见）                  │
│ deferred tools   → DeferredToolRegistry（不可见）          │
│                     预建 Jieba 分词索引（~16ms/50 工具）    │
└─────────────────────────────────────────────────────────┘
        │
        ▼ 每次请求（call / stream）
┌─────────────────────────────────────────────────────────┐
│ AgentLoopExecutor 构造时：                                │
│   registry.createSession() → 独立的 discoveredNames       │
│                                                              │
│ Round 1:                                                 │
│   buildRoundChatClient() → [getTime, tool_search]        │
│   LLM: "帮我查北京天气"                                   │
│   LLM 调用: tool_search("天气 查询")                      │
│   → keyword 匹配到 getWeather                             │
│   → discoveredNames = {getWeather}                        │
│   → 返回: "找到工具: getWeather"                          │
│                                                          │
│ Round 2:                                                 │
│   buildRoundChatClient() → [getTime, getWeather, tool_search] │
│   LLM 直接调用: getWeather(city="北京")                   │
│   → toolMap 包含全部工具，执行成功                         │
└─────────────────────────────────────────────────────────┘
```

### 关键设计

| 设计点 | 说明 |
|--------|------|
| **请求级隔离** | 每次 `call()`/`stream()` 创建独立的 `Session`，`discoveredNames` 请求间不泄漏 |
| **分词索引共享** | Jieba 分词索引在 `build()` 时构建一次，所有请求复用（~16ms/50 工具） |
| **toolMap 全量** | 工具执行查找表（toolMap）包含所有工具，保证任何工具都能被找到 |
| **ChatClient 按需** | 每轮循环重建 ChatClient，只暴露 alwaysLoad + 已发现 + tool_search |

## 使用方式

```java
// alwaysLoad 工具：始终可见，LLM 每轮都能直接调用
ToolCallback[] alwaysLoadTools = ToolCallbacks.from(new CoreTools());

// deferred 工具：按需发现，LLM 需要时通过 tool_search 搜索加载
ToolCallback[] deferredTools = ReactAgent.mergeTools(
        ToolCallbacks.from(new WeatherTools()),
        ToolCallbacks.from(new SlackTools()),
        ToolCallbacks.from(new CalendarTools()),
        ToolCallbacks.from(new EmailTools())
);

ReactAgent agent = ReactAgent.builder()
        .chatModel(chatModel)
        .tools(alwaysLoadTools)                                    // 始终可见
        .deferredTools(ToolSearchConfig.defaults(), deferredTools)  // 按需发现
        .maxRounds(10)
        .build();

// LLM 自动判断是否需要搜索更多工具
String answer = agent.call("帮我查一下北京的天气");
```

### 搜索配置

```java
// 默认配置（HYBRID 模式，最多返回 5 个工具）
ToolSearchConfig.defaults()

// 仅关键词匹配（无额外 LLM 调用，速度最快）
ToolSearchConfig.builder()
        .mode(ToolSearchConfig.Mode.KEYWORD)
        .maxResults(3)
        .build()

// 仅 LLM 选择（关键词匹配不准时使用）
ToolSearchConfig.builder()
        .mode(ToolSearchConfig.Mode.LLM)
        .maxResults(3)
        .build()

// 混合模式（默认）：先关键词，无结果时 LLM 兜底
ToolSearchConfig.builder()
        .mode(ToolSearchConfig.Mode.HYBRID)
        .maxResults(5)
        .build()
```

### 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `mode` | `HYBRID` | 搜索模式：`KEYWORD`、`LLM`、`HYBRID` |
| `maxResults` | `5` | 每次搜索最大返回工具数量 |

## 搜索模式

### KEYWORD 模式

纯关键词匹配，无额外 LLM 调用，延迟最低。

搜索流程：
1. 用 Jieba 分词对 deferred 工具的 name 和 description 预建分词索引
2. 对搜索 query 进行分词
3. 多维度打分匹配

| 匹配维度 | 分数 | 示例 |
|----------|------|------|
| 精确名称匹配 | 100 | query="getWeather" → getWeather |
| 名称 token 匹配 | 50 | query="weather" → getWeather(name token: [get, weather]) |
| 名称子串匹配 | 20 | query="weather" → getWeather |
| 描述 token 匹配 | 10 | query="天气" → getWeather(描述含"天气预报") |
| 描述子串匹配 | 5 | query="Slack" → sendSlackMessage(描述含"Slack") |

### LLM 模式

构建精简 catalog（每个工具一行：`name: description`），一次 LLM 调用选出相关工具。

适合工具描述比较抽象、关键词难以匹配的场景。有额外 LLM 调用成本。

### HYBRID 模式（默认）

先走 KEYWORD，有结果直接返回；无结果时 LLM 兜底。兼顾速度和准确率。

## 请求隔离机制

```
ReactAgent（build 时创建）
├── DeferredToolRegistry（共享不可变）
│   ├── deferredTools Map        ← 共享，工具池
│   ├── catalog（Jieba 分词索引） ← 共享，只构建一次
│   ├── config                   ← 共享
│   └── chatModel                ← 共享
│
├── 请求 1: createSession()
│   ├── discoveredNames = {}      ← 独立，请求 1 专用
│   └── toolSearchCallback        ← 独立，绑定到请求 1 的 discoveredNames
│       → 发现了 getWeather, sendSlackMessage
│
└── 请求 2: createSession()
    ├── discoveredNames = {}      ← 独立，请求 2 专用（空的！）
    └── toolSearchCallback        ← 独立，绑定到请求 2 的 discoveredNames
        → 重新走 tool_search 发现 getWeather
```

不同请求之间的工具发现状态完全隔离，不会互相泄漏。

## 工具分类建议

| 分类 | 放入 | 示例 |
|------|------|------|
| 每轮都可能用到的通用工具 | `tools()`（alwaysLoad） | getTime、bash、read_file |
| 特定领域、按需使用的工具 | `deferredTools()` | sendEmail、queryDatabase、calendarEvent |
| 使用频率不确定的工具 | `deferredTools()` | 按场景灵活判断 |

**经验法则**：如果工具总数 < 20，通常不需要 ToolSearch；超过 30 个时建议启用。

## 相关类

| 类 | 包路径 | 说明 |
|----|--------|------|
| `ToolSearchConfig` | `com.agentx.ai.core.tools.toolsearch` | 搜索配置（record + Builder） |
| `DeferredToolRegistry` | `com.agentx.ai.core.tools.toolsearch` | 延迟工具注册中心，管理共享状态和 Session |
| `ToolSearchTool` | `com.agentx.ai.core.tools.toolsearch` | `tool_search` 元工具实现 |
| `PromptConstants` | `com.agentx.ai.core.prompt` | TOOL_SEARCH_GUIDANCE 提示词 |

## 最佳实践

1. **默认配置即可起步**：`ToolSearchConfig.defaults()` 适合大多数场景
2. **工具命名要清晰**：name 和 description 越准确，关键词匹配越精准
3. **alwaysLoad 控制在 10 个以内**：避免始终可见的工具过多
4. **优先用 KEYWORD 模式**：无额外 LLM 调用，延迟最低；只有关键词匹配不准时才考虑 HYBRID 或 LLM
5. **中文描述效果好**：Jieba 分词对中文支持优秀，工具 description 尽量用中文

> 参考：`ToolSearchTest` 测试类
