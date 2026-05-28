package com.agentx.ai.core.memory.store;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * DataSource 模式的记忆存储配置。
 *
 * 封装 DataSource 和用户画像开关，传入 {@link com.agentx.ai.core.agent.ReactAgent.Builder}。
 * 框架根据此配置自动创建：
 * - {@link AgentChatMemory} — 会话记忆（agentx_session 表）
 * - {@link JdbcMemoryStore} — 用户画像（agentx_memory 表）
 *
 * @author bigchui
 * 
 */
public class DataSourceMemoryStore {

    private final DataSource dataSource;
    private boolean profileMemoryEnabled = true;

    public DataSourceMemoryStore(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.dataSource = dataSource;
    }

    /**
     * 是否启用用户画像记忆。
     *
     * 禁用后，框架只管理短期会话记忆（agentx_session），不做用户画像提取和注入。
     * 适用于对安全性要求较高的场景，防止用户通过对话诱导智能体生成不当记忆。
     *
     * @param enabled true 启用（默认），false 禁用
     * @return this
     */
    public DataSourceMemoryStore profileMemoryEnabled(boolean enabled) {
        this.profileMemoryEnabled = enabled;
        return this;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public boolean isLongTermMemoryEnabled() {
        return profileMemoryEnabled;
    }
}
