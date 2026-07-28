# SubAgent 子代理

v1_1 下最重要的口径不是“SubAgent 能不能独立存会话”，而是：父 Agent 才是会话持久化边界。

## 1. SubAgent 是怎么接入主 Agent 的

SubAgent 不是特殊运行模式，而是被包装成一个普通工具注册给父 Agent。

工具名规则是：

- `call_{name}`

例如一个名为 `translator` 的子代理，会被父 Agent 看到成 `call_translator` 工具。

主 Agent 的 LLM 根据描述决定是否委派，调用后由子代理完成自己的 ReAct 执行，再把结果返回给父 Agent。

## 2. 调用链路长什么样

```text
用户请求
  ↓
父 Agent 决定调用 call_translator
  ↓
translator 子 Agent 在自己的 context window 中执行
  ↓
子 Agent 返回结果
  ↓
父 Agent 把子 Agent 结果当作 tool response 继续推理
  ↓
父 Agent 输出最终回答
```

这里要注意：SubAgent 的输出最终不是直接写进一张“子会话表”，而是作为父 Agent 工具调用链的一部分回到父会话里。

## 3. 什么是“独立 context window”

SubAgent 有自己的：

- `instructions`
- 工具集
- 推理轮次
- 运行时消息列表

所以它在执行期是上下文隔离的。

但这不等于它拥有独立的持久化会话边界。

## 4. 会话持久化边界在哪里

### 4.1 父 Agent 才写 `agentx_session`

v1_1 下，SubAgent 默认不会写自己的 `agentx_session`。

原因不是“刚好没写”，而是框架在把它包装为子代理时，会显式把子代理切到 SubAgent 模式，并关闭 session 持久化。

因此：

- 父 Agent：是 `agentx_session` 的持久化边界
- 子 Agent：不是独立 session 边界

### 4.2 子 Agent 的结果如何进入父会话

子 Agent 的结果会通过父 Agent 的工具响应链路回流。

所以在父会话视角看，看到的是：

- 一条 assistant tool call
- 一条对应 tool response
- 随后父 Agent 基于结果继续回答

也就是说，用户最终能追溯到的是父 Agent 这一侧的消息链。

## 5. trace / session 的区别

SubAgent 在存储上的口径要分开看：

| 能力 | SubAgent 行为 |
|------|---------------|
| `agentx_session` | 默认关闭，不独立写入 |
| `agentx_trace` | 复用父 Agent 的 trace 开关和 TraceStore |

所以当前实现不是“子代理完全无痕”，而是：

- session 以父为边界
- trace 跟随父链路一起审计

## 6. 当前不支持什么

v1_1 下 SubAgent 有明确约束：

| 限制 | 说明 |
|------|------|
| 禁止嵌套 SubAgent | 子 Agent 不能再注册新的 SubAgent 工具 |
| 禁止 AskUser | 子 Agent 不能直接向用户发起 HITL 提问 |
| 禁止 PauseAdvisor | 子 Agent 不能引入自己的暂停审批链路 |

这三条约束连起来的含义就是：当前 SubAgent 仍然是父 Agent 调度下的受控执行单元，不是一个拥有完整暂停恢复能力的独立代理树节点。

## 7. 它支不支持独立中断恢复

当前不支持。

更准确地说：

- SubAgent 没有独立的 PauseAdvisor 链路
- SubAgent 不能自己发起 `ask_user`
- 当前运行时也没有打通“子 Agent 独立快照 + 独立恢复”这条链路

所以如果父 Agent 执行被中断，暂停恢复语义仍然由父 Agent 统一承担。

## 8. 流式场景下事件怎么看

流式模式下，子 Agent 的事件会被转发到父事件流里，并带上 `SubAgentSource`。

这意味着前端或调用方可以区分：

- 这是父 Agent 的事件
- 还是某个子 Agent 的事件

但事件来源可区分，不代表会话存储边界也独立。

## 9. 推荐用哪个样例验证

### `SubAgentSessionTest`

这个样例是当前最直接的验证入口。

它重点证明：

1. 子代理可以在流式模式下被父 Agent 正常调用
2. `agentx_session` 中最终只看到父 Agent 的 session 边界
3. 不会额外冒出一个属于子 Agent 的独立 session 持久化链路

如果你关心“SubAgent 理论上应该不受新 `agentx_session` 影响吗”，这个样例就是答案：

- 会影响父 Agent 的会话链路
- 但子 Agent 自己不会额外落自己的 session

## 10. 小结

记住这一版最关键的口径：

- SubAgent 以 `call_{name}` 工具形式被父 Agent 调用
- SubAgent 有独立 context window，但默认不写自己的 `agentx_session`
- 父 Agent 才是会话持久化边界
- SubAgent 输出通过父 Agent 的 tool response 进入父会话
- 当前禁止嵌套 SubAgent、AskUser、PauseAdvisor
