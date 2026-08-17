package com.agentx.ai.core.hook;

import com.agentx.ai.core.sandbox.ExecutionBackend;
import com.agentx.ai.core.sandbox.Sandbox;
import com.agentx.ai.core.sandbox.SandboxManager;
import com.agentx.ai.core.stage.AgentRuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 沙箱生命周期 Hook。
 *
 * <p>订阅 {@link BeforeCallEvent} / {@link AfterCallEvent}，在调用边界上
 * 获取（acquire）和释放（release）沙箱容器。
 *
 * <p>不处理 Tool 级事件 —— ExecutionBackend 通过 ToolContext 显式传递给工具，
 * 不需要 ThreadLocal。
 *
 * <p>并发安全：沙箱句柄存储在 per-call 的 {@link AgentRuntimeContext}（而非实例字段），
 * 同一 ReactAgent 实例并发处理多个会话时各会话的句柄互不干扰。
 *
 * <p>错误容忍：
 * <ul>
 *   <li>acquire 失败（默认模式）→ 打 ERROR 日志，不设置 ExecutionBackend → Agent 退化为宿主执行</li>
 *   <li>acquire 失败（严格模式）→ 标记 sandboxFailed，工具调用返回错误，不降级宿主执行</li>
 *   <li>release 失败 → 打 ERROR 日志，不影响 Agent 结果</li>
 * </ul>
 *
 * @author bigchui
 */
public class SandboxHook implements AgentHook {

    private static final Logger log = LoggerFactory.getLogger(SandboxHook.class);

    private final SandboxManager manager;

    public SandboxHook(SandboxManager manager) {
        this.manager = manager;
    }

    @Override
    public HookEvent onEvent(HookEvent event) {
        if (event instanceof BeforeCallEvent e) {
            handleBeforeCall(e);
        } else if (event instanceof AfterCallEvent e) {
            handleAfterCall(e);
        }
        return event;
    }

    private void handleBeforeCall(BeforeCallEvent e) {
        AgentRuntimeContext ctx = e.getRuntimeContext();
        try {
            Sandbox sandbox = manager.acquire(ctx);
            ctx.setActiveSandbox(sandbox);
            ExecutionBackend backend = sandbox.getExecutionBackend();
            ctx.setExecutionBackend(backend);
            log.info("[SandboxHook] 沙箱已就绪: {}", sandbox.getContainerName());
        } catch (Exception ex) {
            ctx.setActiveSandbox(null);
            if (manager.getConfig().isStrictMode()) {
                ctx.markSandboxFailed();
                log.error("[SandboxHook] 沙箱获取失败（严格模式），工具调用将拒绝执行", ex);
            } else {
                log.error("[SandboxHook] 沙箱获取失败，退化为宿主执行", ex);
            }
        }
    }

    private void handleAfterCall(AfterCallEvent e) {
        Sandbox sandbox = e.getRuntimeContext().getActiveSandbox();
        if (sandbox == null) {
            return;
        }
        e.getRuntimeContext().setActiveSandbox(null);
        manager.release(sandbox, e.getRuntimeContext());
    }

    @Override
    public int priority() {
        return Integer.MAX_VALUE - 1;
    }
}
