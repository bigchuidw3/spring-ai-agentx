package com.agentx.ai.core.utils;

import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具合并工具类，支持按名称去重。
 *
 * @author bigchui
 */
public final class ToolMergeUtil {

    private ToolMergeUtil() {
    }

    /**
     * 合并多个工具数组，按工具名称去重（相同名称只保留第一个）。
     *
     * @param toolArrays 工具数组
     * @return 合并并去重后的工具数组
     */
    @SafeVarargs
    public static ToolCallback[] mergeTools(ToolCallback[]... toolArrays) {
        List<ToolCallback> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (toolArrays != null) {
            for (ToolCallback[] array : toolArrays) {
                if (array != null) {
                    for (ToolCallback tool : array) {
                        String name = tool.getToolDefinition().name();
                        if (seen.add(name)) {
                            result.add(tool);
                        }
                    }
                }
            }
        }
        return result.toArray(new ToolCallback[0]);
    }
}
