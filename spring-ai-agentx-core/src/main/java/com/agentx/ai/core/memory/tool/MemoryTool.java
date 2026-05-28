package com.agentx.ai.core.memory.tool;

import com.agentx.ai.core.memory.model.MemoryItem;
import com.agentx.ai.core.memory.model.MemoryType;
import com.agentx.ai.core.memory.store.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 记忆工具 - 让 Agent 能够主动读写长期记忆。
 *
 * 提供三个工具：
 * - saveMemory - 保存记忆到长期存储
 * - getMemory - 从长期存储获取记忆
 * - searchMemory - 搜索相关记忆
 *
 * 使用方式：
 * MemoryStore memoryStore = new InMemoryMemoryStore();
 * ReactAgent agent = ReactAgent.builder()
 *     .chatModel(chatModel)
 *     .memoryStore(memoryStore)
 *     .tools(MemoryTool.create(memoryStore))
 *     .build();
 *
 * @author bigchui
 * 
 */
public class MemoryTool {

    private static final Logger log = LoggerFactory.getLogger(MemoryTool.class);

    private final MemoryStore memoryStore;

    private MemoryTool(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    /**
     * 创建全部三个记忆工具。
     *
     * @param memoryStore 记忆存储
     * @return 工具数组 [saveMemory, getMemory, searchMemory]
     */
    public static ToolCallback[] create(MemoryStore memoryStore) {
        MemoryTool instance = new MemoryTool(memoryStore);
        return ToolCallbacks.from(instance);
    }

    /**
     * 从 ToolContext 中获取 userId。
     */
    private String getUserId(ToolContext context) {
        if (context == null) {
            return null;
        }
        return (String) context.getContext().get("userId");
    }

    @Tool(description = "保存信息到长期记忆。当你从对话中发现值得长期记住的用户信息时使用此工具。")
    public String saveMemory(
            @ToolParam(description = "记忆类型: PROFILE(用户画像), PREFERENCE(用户偏好), INSTRUCTION(行为指令), FACT(客观事实)") String type,
            @ToolParam(description = "记忆内容") String content,
            @ToolParam(description = "一句话描述（可选）") String description,
            ToolContext context) {

        String userId = getUserId(context);
        if (userId == null) {
            return "{\"error\": \"userId is required\"}";
        }

        MemoryType memoryType;
        try {
            memoryType = MemoryType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return "{\"error\": \"Invalid type: " + type + ". Valid types: PROFILE, PREFERENCE, INSTRUCTION, FACT\"}";
        }

        MemoryItem item = MemoryItem.builder()
                .userId(userId)
                .type(memoryType)
                .content(content)
                .description(description != null && !description.isBlank() ? description : content)
                .build();

        memoryStore.save(userId, item);
        log.debug("Agent saved memory: userId={}, type={}, content={}", userId, memoryType, content);

        return "{\"message\": \"Memory saved\", \"id\": \"" + item.getId() + "\"}";
    }

    @Tool(description = "从长期记忆中获取用户的相关信息。不传 type 则获取全部。")
    public String getMemory(
            @ToolParam(description = "记忆类型过滤（可选）: PROFILE, PREFERENCE, INSTRUCTION, FACT") String type,
            ToolContext context) {

        String userId = getUserId(context);
        if (userId == null) {
            return "{\"error\": \"userId is required\"}";
        }

        List<MemoryItem> memories;
        if (type != null && !type.isBlank()) {
            try {
                MemoryType memoryType = MemoryType.valueOf(type.toUpperCase());
                memories = memoryStore.findByUserIdAndType(userId, memoryType);
            } catch (IllegalArgumentException e) {
                return "{\"error\": \"Invalid type: " + type + "\"}";
            }
        } else {
            memories = memoryStore.findByUserId(userId);
        }

        String memoryText = memories.stream()
                .map(m -> "- [" + m.getType().name() + "] " + m.getContent())
                .collect(Collectors.joining("\n"));

        if (memoryText.isEmpty()) {
            return "No memories found for this user.";
        }
        return "Found " + memories.size() + " memories:\n" + memoryText;
    }

    @Tool(description = "语义搜索长期记忆，查找与查询关键词相关的信息。")
    public String searchMemory(
            @ToolParam(description = "搜索查询") String query,
            @ToolParam(description = "返回条数上限，默认5") int topK,
            ToolContext context) {

        String userId = getUserId(context);
        if (userId == null) {
            return "{\"error\": \"userId is required\"}";
        }

        int limit = topK > 0 ? topK : 5;
        List<MemoryItem> results = memoryStore.search(userId, query, limit);

        if (results.isEmpty()) {
            return "No relevant memories found for query: " + query;
        }

        String memoryText = results.stream()
                .map(m -> "- [" + m.getType().name() + "] " + m.getContent())
                .collect(Collectors.joining("\n"));

        return "Found " + results.size() + " relevant memories:\n" + memoryText;
    }
}
