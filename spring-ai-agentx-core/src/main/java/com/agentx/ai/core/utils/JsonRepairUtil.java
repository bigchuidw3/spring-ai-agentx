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

        // 将 JSON 结构位置的单引号替换为双引号（JSON 标准要求双引号）
        // 关键：必须跳过"双引号字符串值内部"的单引号，否则会破坏内容
        // 例如 "代码中调用 $_SERVER['REQUEST_METHOD']" 里的单引号绝不能动
        // 正则无法稳定追踪字符串边界，这里用状态机按字符扫描
        StringBuilder sb = new StringBuilder(text.length());
        boolean inString = false;  // 是否处于双引号字符串内部
        boolean escape = false;    // 当前字符是否被反斜杠转义（仅字符串内有效）
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                sb.append(c);
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else {
                if (c == '"') {
                    inString = true;
                    sb.append(c);
                } else if (c == '\'') {
                    // 双引号字符串外部的单引号视为 JSON 结构性引号，替换为双引号
                    sb.append('"');
                } else {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
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

    public static void main(String[] args) {
        System.out.println(fixJson("{\n" +
                "  \"verdict\": \"clean\",\n" +
                "  \"confidence\": 0.82,\n" +
                "  \"threat_type\": null,\n" +
                "  \"conclusion\": \"代码为普通 PHP 文件上传接收脚本，仅打印上传文件名，不存在恶意行为或 WebShell 特征。\",\n" +
                "  \"technical_details\": \"代码结构分析：\\n1. 混淆检测：无任何混淆手段，代码为明文可读 PHP。\\n2. 危险行为检测：\\n   - 代码仅定义了上传目录变量 $upload_dir = \\\"/tmp/uploads/\\\"，但未实际使用该变量进行任何文件写入、移动或执行操作；\\n   - 通过 $_SERVER['REQUEST_METHOD'] 判断请求方法为 POST 后，仅执行 echo 输出 $_FILES['file']['name']（文件名字符串），无文件落地、无代码执行、无命令执行；\\n   - 无 eval、exec、system、passthru、popen、proc_open、assert、preg_replace /e 等任何危险函数调用；\\n   - 无动态代码生成、无网络外联、无持久化操作。\\n3. 整体语义：该脚本功能极为有限，仅回显上传文件名，不具备 WebShell 的核心能力（代码执行/命令执行/文件写入）。\\n4. 注意点：$upload_dir 定义但未使用，可能为开发中的不完整代码；文件名直接拼接输出存在轻微 XSS 风险，但不构成服务器端 WebShell 威胁。\",\n" +
                "  \"risk_assessment\": null,\n" +
                "  \"recommendations\": \"1. 当前代码不构成 WebShell 威胁，可保留正常使用；\\n2. 建议对 $_FILES['file']['name'] 输出进行 htmlspecialchars() 转义，防止潜在的反射型 XSS；\\n3. 若后续补全文件上传落地逻辑，需严格校验文件类型（白名单）、重命名文件、禁止上传可执行脚本，并确保上传目录不可被 Web 直接访问；\\n4. 建议结合 WAF 对上传接口进行防护。\"\n" +
                "}"));
    }
}

