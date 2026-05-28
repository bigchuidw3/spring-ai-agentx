package com.agentx.ai.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI AgentX 配置属性。（TODO 暂时保留，后续增加自动注入）
 *
 * @author bigchui
 * @version 1.0.0-M1
 */
@ConfigurationProperties(prefix = "spring-ai.agentx")
public class AgentXProperties {

    /**
     * 最大迭代轮次
     */
    private int maxRounds = 10;

    /**
     * 工作目录
     */
    private String workingDir;

    /**
     * Bash 工具配置
     */
    private Bash bash = new Bash();

    /**
     * 是否启用 Skills
     */
    private boolean skillsEnabled = false;

    /**
     * Skills 扫描目录
     */
    private List<String> skillsDirectories = new ArrayList<>(List.of(".claude/skills"));

    /**
     * 是否启用 MCP
     */
    private boolean mcpEnabled = false;

    /**
     * 获取最大轮次
     *
     * @return 最大轮次
     */
    public int getMaxRounds() {
        return maxRounds;
    }

    /**
     * 设置最大轮次
     *
     * @param maxRounds 最大轮次
     */
    public void setMaxRounds(int maxRounds) {
        this.maxRounds = maxRounds;
    }

    /**
     * 获取工作目录
     *
     * @return 工作目录
     */
    public String getWorkingDir() {
        return workingDir;
    }

    /**
     * 设置工作目录
     *
     * @param workingDir 工作目录
     */
    public void setWorkingDir(String workingDir) {
        this.workingDir = workingDir;
    }

    /**
     * 获取 Bash 配置
     *
     * @return Bash 配置
     */
    public Bash getBash() {
        return bash;
    }

    /**
     * 设置 Bash 配置
     *
     * @param bash Bash 配置
     */
    public void setBash(Bash bash) {
        this.bash = bash;
    }

    /**
     * 是否启用 Skills
     *
     * @return true if skills enabled
     */
    public boolean isSkillsEnabled() {
        return skillsEnabled;
    }

    /**
     * 设置是否启用 Skills
     *
     * @param skillsEnabled true to enable
     */
    public void setSkillsEnabled(boolean skillsEnabled) {
        this.skillsEnabled = skillsEnabled;
    }

    /**
     * 获取 Skills 扫描目录列表
     *
     * @return 目录列表
     */
    public List<String> getSkillsDirectories() {
        return skillsDirectories;
    }

    /**
     * 设置 Skills 扫描目录列表
     *
     * @param skillsDirectories 目录列表
     */
    public void setSkillsDirectories(List<String> skillsDirectories) {
        this.skillsDirectories = skillsDirectories;
    }

    /**
     * 是否启用 MCP
     *
     * @return true if MCP enabled
     */
    public boolean isMcpEnabled() {
        return mcpEnabled;
    }

    /**
     * 设置是否启用 MCP
     *
     * @param mcpEnabled true to enable
     */
    public void setMcpEnabled(boolean mcpEnabled) {
        this.mcpEnabled = mcpEnabled;
    }

    /**
     * Bash 工具配置
     */
    public static class Bash {
        /**
         * 超时时间（毫秒）
         */
        private Long timeoutMs;

        /**
         * 最大输出行数
         */
        private Integer maxLines;

        /**
         * 最大输出字节数
         */
        private Integer maxBytes;

        public Long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(Long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public Integer getMaxLines() {
            return maxLines;
        }

        public void setMaxLines(Integer maxLines) {
            this.maxLines = maxLines;
        }

        public Integer getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(Integer maxBytes) {
            this.maxBytes = maxBytes;
        }
    }
}
