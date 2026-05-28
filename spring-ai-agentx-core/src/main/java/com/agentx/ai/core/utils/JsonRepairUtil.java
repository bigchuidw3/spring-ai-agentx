package com.agentx.ai.core.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON 修复工具类
 * 用于处理大模型返回的可能包含错误的 JSON 字符串
 */
public class JsonRepairUtil {

    private static final Logger log = LoggerFactory.getLogger(JsonRepairUtil.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 修复并解析 JSON 字符串
     *
     * @param jsonString 可能包含错误的 JSON 字符串
     * @return 修复后的 JSON 字符串
     */
    public static String fixJson(String jsonString) {
        if (jsonString == null || jsonString.trim().isEmpty()) {
            return "{}";
        }

        String fixed = jsonString.trim();

        // 1. 提取 JSON 内容（移除 markdown 代码块标记）
        fixed = extractJsonFromMarkdown(fixed);

        // 2. 移除 JSON 前后的非法字符
        fixed = removeLeadingTrailingGarbage(fixed);

        // 3. 修复常见的引号问题
        fixed = fixQuotes(fixed);

        // 4. 修复尾部逗号问题
        fixed = fixTrailingCommas(fixed);

        // 5. 修复缺失的引号
        fixed = fixMissingQuotes(fixed);

        // 6. 修复转义字符问题
        fixed = fixEscapeChars(fixed);

        // 7. 尝试验证并返回
        try {
            // 验证 JSON 是否有效
            objectMapper.readTree(fixed);
            return fixed;
        } catch (Exception e) {
            log.warn("JSON 修复后仍然无效，返回原始字符串。Error: {}", e.getMessage());
            // 最后的容错：如果还是无效，尝试包装成简单对象
            return wrapAsSimpleJson(jsonString);
        }
    }

    /**
     * 修复并解析为 JsonNode
     *
     * @param jsonString JSON 字符串
     * @return JsonNode 对象
     */
    public static JsonNode fixAndParse(String jsonString) {
        String fixed = fixJson(jsonString);
        try {
            return objectMapper.readTree(fixed);
        } catch (Exception e) {
            log.error("JSON 解析失败", e);
            return objectMapper.createObjectNode();
        }
    }

    /**
     * 从 Markdown 代码块中提取 JSON
     */
    private static String extractJsonFromMarkdown(String text) {
        // 匹配 ```json ... ``` 或 ``` ... ```
        Pattern pattern = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return text;
    }

    /**
     * 移除 JSON 前后的垃圾字符
     */
    private static String removeLeadingTrailingGarbage(String text) {
        // 找到第一个 { 或 [
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' || c == '[') {
                start = i;
                break;
            }
        }

        // 找到最后一个 } 或 ]
        int end = -1;
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '}' || c == ']') {
                end = i + 1;
                break;
            }
        }

        if (start != -1 && end != -1 && start < end) {
            return text.substring(start, end);
        }
        return text;
    }

    /**
     * 修复引号问题（中文引号、单引号等）
     */
    private static String fixQuotes(String text) {
        // 替换中文引号为英文引号
        text = text.replace("“", "\"").replace("”", "\"");
        text = text.replace("‘", "'").replace("’", "'");

        // 将单引号替换为双引号（JSON 标准要求双引号）
        // 只替换 JSON 结构位置的单引号（紧跟 : [ { , 之后），避免误替换字符串值内部的单引号
        // 例如：不能把 "提出了'重构'这一概念" 中的单引号替换掉
        text = text.replaceAll("([: \\[\\{,]\\s*)'([^']*?)'(?=\\s*[:,\\]}])", "$1\"$2\"");

        return text;
    }

    /**
     * 修复尾部逗号问题
     */
    private static String fixTrailingCommas(String text) {
        // 移除对象中的尾部逗号: ,}
        text = text.replaceAll(",\\s*}", "}");
        // 移除数组中的尾部逗号: ,]
        text = text.replaceAll(",\\s*]", "]");
        return text;
    }

    /**
     * 修复缺失的引号（针对键名）
     */
    private static String fixMissingQuotes(String text) {
        // 为没有引号的键名添加引号
        // 匹配模式: word: (不带引号的键)
        text = text.replaceAll("([{,]\\s*)([a-zA-Z_][a-zA-Z0-9_]*)\\s*:", "$1\"$2\":");
        return text;
    }

    /**
     * 修复转义字符问题
     */
    private static String fixEscapeChars(String text) {
        // 修复常见的非法转义字符
        // 注意：这里需要谨慎处理，避免破坏合法的转义字符

        // 移除字符串中的非法换行符
        text = text.replaceAll("(?<!\\\\)\\n", " ");
        text = text.replaceAll("(?<!\\\\)\\r", " ");
        text = text.replaceAll("(?<!\\\\)\\t", " ");

        return text;
    }

    /**
     * 将文本包装成简单的 JSON 对象
     */
    private static String wrapAsSimpleJson(String text) {
        try {
            // 尝试转义特殊字符后包装
            String escaped = text.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
            return "{\"content\":\"" + escaped + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"Invalid JSON\"}";
        }
    }

    /**
     * 验证 JSON 字符串是否有效
     *
     * @param jsonString JSON 字符串
     * @return true 如果有效，false 如果无效
     */
    public static boolean isValidJson(String jsonString) {
        try {
            objectMapper.readTree(jsonString);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 美化 JSON 字符串
     *
     * @param jsonString JSON 字符串
     * @return 格式化后的 JSON 字符串
     */
    public static String prettify(String jsonString) {
        try {
            Object json = objectMapper.readValue(jsonString, Object.class);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
        } catch (Exception e) {
            log.error("JSON 美化失败", e);
            return jsonString;
        }
    }
}

