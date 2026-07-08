package com.agentx.ai.core.interrupt;

import com.agentx.ai.core.model.PauseState;
import com.agentx.ai.core.model.RunnableParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的 {@link PauseStateStore} 实现。
 *
 * <p>用于单节点部署、单元测试和开发期验证。不具备跨进程持久化能力，
 * 进程重启后状态丢失。生产环境跨进程恢复请使用 {@link JdbcPauseStateStore}。
 *
 * <p>过期清理策略：以 {@link PauseState#getInterruptedAt()} 为基准，
 * 超过 {@link #defaultTtlMillis} 的状态视为过期。{@link PauseState#getInterruptedAt()} 为 0 时永不过期。
 *
 * @author bigchui
 */
public class InMemoryPauseStateStore implements PauseStateStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryPauseStateStore.class);

    /** 默认 TTL：7 天 */
    private static final long DEFAULT_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000;

    private final Map<String, PauseState> store = new ConcurrentHashMap<>();
    private final long defaultTtlMillis;

    public InMemoryPauseStateStore() {
        this(DEFAULT_TTL_MILLIS);
    }

    /**
     * 自定义 TTL（毫秒）。
     *
     * @param defaultTtlMillis 状态过期阈值，必须 &gt; 0
     */
    public InMemoryPauseStateStore(long defaultTtlMillis) {
        if (defaultTtlMillis <= 0) {
            throw new IllegalArgumentException("defaultTtlMillis must be positive");
        }
        this.defaultTtlMillis = defaultTtlMillis;
    }

    @Override
    public void save(PauseState state) {
        Objects.requireNonNull(state, "state must not be null");
        String conversationId = requireConversationId(state);
        store.put(conversationId, state);
        log.debug("[InMemoryPauseStateStore] saved: conversationId={}", conversationId);
    }

    @Override
    public PauseState findByConversationId(String conversationId) {
        if (conversationId == null) {
            return null;
        }
        return store.get(conversationId);
    }

    @Override
    public boolean exists(String conversationId) {
        if (conversationId == null) {
            return false;
        }
        return store.containsKey(conversationId);
    }

    @Override
    public boolean delete(String conversationId) {
        if (conversationId == null) {
            return false;
        }
        return store.remove(conversationId) != null;
    }

    @Override
    public int deleteExpired() {
        long now = System.currentTimeMillis();
        int removed = 0;
        Iterator<Map.Entry<String, PauseState>> it = store.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, PauseState> e = it.next();
            PauseState state = e.getValue();
            if (state == null) {
                it.remove();
                continue;
            }
            long interruptedAt = state.getInterruptedAt();
            if (interruptedAt > 0 && (now - interruptedAt) > defaultTtlMillis) {
                it.remove();
                removed++;
                log.debug("[InMemoryPauseStateStore] expired: conversationId={}", e.getKey());
            }
        }
        return removed;
    }

    private static String requireConversationId(PauseState state) {
        RunnableParams params = state.getParams();
        if (params == null || params.getConversationId() == null) {
            throw new IllegalArgumentException("PauseState.params.conversationId must not be null");
        }
        return params.getConversationId();
    }
}
