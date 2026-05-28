package com.agentx.ai.core.stage;

import com.agentx.ai.core.model.AgentStreamEvent;
import com.agentx.ai.core.model.StageContext;
import com.agentx.ai.core.model.StageOutputProvider;
import com.agentx.ai.core.model.StageTiming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 阶段输出管理器。
 *
 * 按 {@link StageTiming} 分组管理 {@link StageOutputProvider}，
 * 在生命周期钩子点依次调用 Provider 并通过 emitter 发出 {@link AgentStreamEvent.StageOutput} 事件。
 *
 * <p>不注册 Provider 时，所有方法为空操作，零开销。
 *
 * @author bigchui
 * 
 */
public class StageOutputManager {

    private static final Logger log = LoggerFactory.getLogger(StageOutputManager.class);

    private final Map<StageTiming, List<StageOutputProvider>> providers;
    private final boolean empty;

    public StageOutputManager(List<StageOutputProvider> providerList) {
        if (providerList == null || providerList.isEmpty()) {
            this.providers = Collections.emptyMap();
            this.empty = true;
        } else {
            this.providers = providerList.stream()
                    .collect(Collectors.groupingBy(StageOutputProvider::timing));
            this.empty = false;
        }
    }

    /**
     * 空管理器（无 Provider）。
     */
    public static final StageOutputManager EMPTY = new StageOutputManager(null);

    /**
     * AgentStart 之后钩子。
     */
    public void afterStart(StageContext ctx, Consumer<AgentStreamEvent> emitter) {
        invoke(StageTiming.AFTER_START, ctx, emitter);
    }

    /**
     * 每个 ToolEnd 之后钩子。
     */
    public void afterToolEnd(StageContext ctx, Consumer<AgentStreamEvent> emitter) {
        invoke(StageTiming.AFTER_TOOL_END, ctx, emitter);
    }

    /**
     * Complete 之前钩子。
     */
    public void beforeComplete(StageContext ctx, Consumer<AgentStreamEvent> emitter) {
        invoke(StageTiming.BEFORE_COMPLETE, ctx, emitter);
    }

    /**
     * 是否没有注册任何 Provider。
     */
    public boolean isEmpty() {
        return empty;
    }

    private void invoke(StageTiming timing, StageContext ctx, Consumer<AgentStreamEvent> emitter) {
        List<StageOutputProvider> list = providers.getOrDefault(timing, List.of());
        for (StageOutputProvider provider : list) {
            try {
                Object output = provider.produce(ctx);
                if (output != null) {
                    emitter.accept(new AgentStreamEvent.StageOutput(provider.name(), output));
                }
            } catch (Exception e) {
                log.error("StageOutputProvider '{}' 执行失败: {}", provider.name(), e.getMessage(), e);
            }
        }
    }
}
