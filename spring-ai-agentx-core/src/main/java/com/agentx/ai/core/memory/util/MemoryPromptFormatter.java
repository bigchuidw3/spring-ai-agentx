package com.agentx.ai.core.memory.util;

import com.agentx.ai.core.memory.model.MemoryItem;
import com.agentx.ai.core.memory.model.MemoryType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆提示词格式化工具。
 *
 * 将记忆条目按类型分组，格式化为结构化的系统提示词文本，
 * 供 Agent 理解用户的长期记忆上下文。
 *
 * 格式化结果示例：
 * ## 用户画像
 * 张三，产品经理，擅长数据分析
 *
 * ## 用户偏好
 * 偏好中文回复
 * 喜欢表格形式展示数据
 *
 * ## 行为要求
 * 不要解释原理直接给答案
 *
 * ## 已知事实
 * 用户系统使用 MySQL 8.0
 * 公司使用飞书进行沟通
 *
 * @author bigchui
 * 
 */
public class MemoryPromptFormatter {

    private static final Map<MemoryType, String> TYPE_LABELS = Map.of(
            MemoryType.PROFILE, "用户画像",
            MemoryType.PREFERENCE, "用户偏好",
            MemoryType.INSTRUCTION, "行为要求",
            MemoryType.FACT, "已知事实"
    );

    private MemoryPromptFormatter() {
    }

    /**
     * 将记忆列表格式化为系统提示词文本。
     *
     * @param memories 记忆条目列表
     * @return 格式化后的文本，如果记忆为空返回空字符串
     */
    public static String format(List<MemoryItem> memories) {
        if (memories == null || memories.isEmpty()) {
            return "";
        }

        // 按类型分组
        EnumMap<MemoryType, List<String>> grouped = new EnumMap<>(MemoryType.class);
        for (MemoryType type : MemoryType.values()) {
            grouped.put(type, new ArrayList<>());
        }

        for (MemoryItem item : memories) {
            grouped.get(item.getType()).add(item.getContent());
        }

        StringBuilder sb = new StringBuilder();

        for (MemoryType type : MemoryType.values()) {
            List<String> contents = grouped.get(type);
            if (!contents.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append("## ").append(TYPE_LABELS.get(type)).append("\n");
                for (String content : contents) {
                    sb.append("- ").append(content).append("\n");
                }
            }
        }

        return sb.toString();
    }

    /**
     * 构建完整的记忆提示词区块（包含标题）。
     *
     * @param memories 记忆条目列表
     * @return 完整的记忆区块文本
     */
    public static String formatSection(List<MemoryItem> memories) {
        String content = format(memories);
        if (content.isEmpty()) {
            return "";
        }
        return "# 长期记忆\n\n" + content;
    }
}
