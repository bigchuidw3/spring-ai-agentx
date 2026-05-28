package com.agentx.ai.core.tools.toolsearch;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 延迟工具注册中心。
 * <p>
 * 管理延迟加载的工具池和搜索配置。由 {@code ReactAgent.Builder} 在 build() 时创建，
 * 生命周期与 ReactAgent 实例相同。
 * <p>
 * 共享不可变状态（跨请求复用）：
 * <ul>
 *   <li>deferredTools — 延迟工具池</li>
 *   <li>catalog — Jieba 分词索引（构建成本约 16ms/50 工具）</li>
 *   <li>config / chatModel</li>
 * </ul>
 * <p>
 * 每次请求通过 {@link #createSession()} 创建独立的 {@link Session}，
 * Session 包含请求级别的 discoveredNames 和 toolSearchCallback，
 * 确保不同请求之间的工具发现状态完全隔离。
 *
 * @author bigchui
 * 
 */
public class DeferredToolRegistry {

    // ==================== 共享不可变状态 ====================

    private final Map<String, ToolCallback> deferredTools;
    private final List<ToolSearchTool.TokenizedTool> catalog;
    private final ToolSearchConfig config;
    private final ChatModel chatModel;

    private DeferredToolRegistry(Map<String, ToolCallback> deferredTools,
                                 List<ToolSearchTool.TokenizedTool> catalog,
                                 ToolSearchConfig config,
                                 ChatModel chatModel) {
        this.deferredTools = deferredTools;
        this.catalog = catalog;
        this.config = config;
        this.chatModel = chatModel;
    }

    /**
     * 创建 DeferredToolRegistry。
     * <p>
     * 在 ReactAgent.Builder.build() 中调用，构建共享的不可变状态。
     *
     * @param config    搜索配置
     * @param tools     延迟工具列表
     * @param chatModel ChatModel（LLM 模式用，nullable）
     * @return DeferredToolRegistry 实例
     */
    public static DeferredToolRegistry create(ToolSearchConfig config,
                                              List<ToolCallback> tools,
                                              ChatModel chatModel) {
        Objects.requireNonNull(config, "ToolSearchConfig must not be null");
        Objects.requireNonNull(tools, "deferred tools must not be null");

        // 构建 deferred 工具池（name → ToolCallback）
        Map<String, ToolCallback> deferredMap = new LinkedHashMap<>();
        for (ToolCallback tool : tools) {
            String name = tool.getToolDefinition().name();
            deferredMap.put(name, tool);
        }

        Map<String, ToolCallback> immutableMap = Collections.unmodifiableMap(deferredMap);

        // 预建分词索引（共享，避免每次请求重复分词）
        List<ToolSearchTool.TokenizedTool> catalog = ToolSearchTool.buildCatalog(immutableMap);

        return new DeferredToolRegistry(immutableMap, catalog, config, chatModel);
    }

    /**
     * 创建请求级别的 Session。
     * <p>
     * 每次 AgentLoopExecutor 构造时调用，返回独立的 discoveredNames 和 toolSearchCallback。
     * 不同请求之间的工具发现状态完全隔离，避免并发泄漏。
     *
     * @return Session 实例
     */
    public Session createSession() {
        return new Session();
    }

    /**
     * 获取所有 deferred 工具映射（不可变）。
     *
     * @return name → ToolCallback
     */
    public Map<String, ToolCallback> getAllDeferredTools() {
        return deferredTools;
    }

    /**
     * 请求级别的会话状态。
     * <p>
     * 包含独立的 discoveredNames 集合和 toolSearchCallback，
     * 每次 {@link #createSession()} 创建新实例，确保请求间隔离。
     * <p>
     * 作为内部类，可直接访问外部 DeferredToolRegistry 的共享状态。
     */
    public class Session {

        private final Set<String> discoveredNames;
        private final ToolCallback toolSearchCallback;

        private Session() {
            this.discoveredNames = ConcurrentHashMap.newKeySet();
            this.toolSearchCallback = ToolSearchTool.createWithCatalog(
                    config, deferredTools, catalog, discoveredNames, chatModel);
        }

        /**
         * 获取本轮已发现的 deferred 工具列表。
         *
         * @return 已发现的 ToolCallback 列表
         */
        public List<ToolCallback> getActiveDeferredTools() {
            if (discoveredNames.isEmpty()) {
                return List.of();
            }
            List<ToolCallback> active = new ArrayList<>();
            for (String name : discoveredNames) {
                ToolCallback tool = deferredTools.get(name);
                if (tool != null) {
                    active.add(tool);
                }
            }
            return active;
        }

        /**
         * 获取 tool_search 元工具的 ToolCallback。
         *
         * @return ToolCallback
         */
        public ToolCallback getToolSearchCallback() {
            return toolSearchCallback;
        }

        /**
         * 获取已发现的工具名称集合。
         *
         * @return 已发现名称集合
         */
        public Set<String> getDiscoveredNames() {
            return discoveredNames;
        }
    }
}
