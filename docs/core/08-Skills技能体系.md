# Skills 技能体系

基于 [spring-ai-agent-utils](https://github.com/springaicommunity/spring-ai-agent-utils) 的 SkillsTool，支持渐进式披露（Progressive Disclosure）：LLM 先看到所有技能的摘要信息，按需调用工具加载完整内容。这样可以避免一次性将大量指令注入上下文，节约 Token 并提高工具选择准确性。

## 工作原理

```
注册 SkillsTool → Agent 启动
    │
    ▼
LLM 看到所有 Skill 的 name + description（摘要）
    │
    ▼
LLM 根据任务选择合适的 Skill
    │
    ▼
调用 SkillsTool 加载完整 Skill 内容（SKILL.md）
    │
    ▼
LLM 基于完整 Skill 指令执行任务
```

## 基本用法

### 创建 SkillsTool

```java
// 创建 SkillsTool，指定技能目录
ToolCallback[] skillsTools = new ToolCallback[]{
        SkillsTool.builder()
                .addSkillsDirectory("C:\\Users\\Lenovo\\.claude\\skills")            // 技能目录
                .build()
};
```

### 注册到 Agent

```java
// 与其他工具一起注册
ReactAgent agent = ReactAgent.builder()
        .chatModel(chatModel)
        .tools(mergeTools(FileSystemTools.create(), BashTool.create(), skillsTools))
        .maxRounds(100)
        .build();

// Agent 会根据任务自动选择并加载合适的 Skill
agent.stream("帮我使用 PPT skill，做一个关于 AI 发展趋势的 PPT，5页，科技风")
        .doOnNext(chunk -> System.out.print(chunk))
        .blockLast();
```

### 多个技能目录

可以注册多个技能目录：

```java
ToolCallback[] skillsTools = new ToolCallback[]{
        SkillsTool.builder()
                .addSkillsDirectory("/path/to/skills/dir1")
                .addSkillsDirectory("/path/to/skills/dir2")
                .build()
};
```

## Skills 目录结构

Skills 基于 Claude Code 的 Skills 规范，每个 Skill 是一个包含 `SKILL.md` 的目录：

```
skills/
├── domain-creator/
│   └── SKILL.md          # 技能定义（name, description, prompt）
├── ppt-creator/
│   ├── SKILL.md
│   ├── references/       # 参考资料
│   └── scripts/          # 脚本文件
└── vulnerability-scanner/
    └── SKILL.md
```

## SKILL.md 格式

每个 Skill 目录下必须有一个 `SKILL.md` 文件，定义技能的元信息和提示词：

```markdown
---
name: ppt-creator
description: 创建专业PPT演示文稿，支持多种布局和风格
userInvocable: true
---

# PPT Creator

你是一个专业的PPT创建助手...

## 技能说明
（完整的技能提示词，Agent 加载后会获得这些指令）

## 使用规则
...
```

### SKILL.md 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | String | 是 | 技能名称，用于工具调用时的标识 |
| `description` | String | 是 | 技能描述，LLM 根据描述选择技能 |
| `userInvocable` | Boolean | 否 | 是否允许用户直接调用（默认 true） |

### 技能内容

`SKILL.md` 的正文部分是完整的技能提示词。当 Agent 调用工具加载技能后，这些内容会注入到上下文中，Agent 按照这些指令执行任务。

## SkillsTool 在上下文压缩中的保护

SkillsTool 在[上下文压缩](11-上下文压缩.md)中是内置保护的工具。也就是说，当 Agent 执行多轮后触发上下文压缩时，SkillsTool 加载的技能内容不会被压缩或丢失。

这是因为 `ContextPolicy` 的 `protectedTools` 默认包含 SkillsTool，确保技能指令在整个执行过程中保持完整。

## 最佳实践

1. **description 要精准**：LLM 根据描述选择技能，描述不准确会导致错误选择
2. **技能内容要详细**：技能提示词越详细，Agent 执行效果越好
3. **利用 references 目录**：将参考资料放在技能目录下，Agent 可以通过文件工具读取
4. **合理设置 maxRounds**：复杂技能可能需要多轮工具调用，确保轮次足够
5. **一个技能一个目录**：保持技能独立，便于维护和复用

## 相关类

| 类 | 包路径 | 说明 |
|----|--------|------|
| `SkillsTool` | `com.agentx.ai.core.tools`（spring-ai-agent-utils） | 技能加载工具 |

> 参考：`AgentSkillsTest` 测试类
