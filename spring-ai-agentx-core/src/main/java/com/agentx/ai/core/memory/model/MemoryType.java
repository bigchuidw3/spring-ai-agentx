package com.agentx.ai.core.memory.model;

/**
 * 长期记忆类型枚举。
 *
 * 定义四种语义类型，覆盖 Agent 需要理解的四个维度：
 * - PROFILE - 用户是谁（身份、角色、背景）
 * - PREFERENCE - 用户喜欢什么（偏好、习惯）
 * - INSTRUCTION - 用户要求怎么做（行为指令、规则）
 * - FACT - 有什么事实需要记住（客观信息）
 *
 * @author bigchui
 * 
 */
public enum MemoryType {

    /**
     * 用户画像 - 用户是谁。
     *
     * 例: "张三, 产品经理, 擅长数据分析"
     */
    PROFILE,

    /**
     * 用户偏好 - 用户喜欢什么、习惯什么。
     *
     * 例: "偏好中文回复", "喜欢表格形式展示数据"
     */
    PREFERENCE,

    /**
     * 行为指令 - 用户要求 Agent 怎么做（纠正/确认后的规则）。
     *
     * 例: "不要解释原理直接给答案", "回复附带代码示例"
     */
    INSTRUCTION,

    /**
     * 客观事实 - 从对话中学到的关于用户的事实信息。
     *
     * 例: "用户的系统使用 MySQL 8.0", "公司使用飞书"
     */
    FACT
}
