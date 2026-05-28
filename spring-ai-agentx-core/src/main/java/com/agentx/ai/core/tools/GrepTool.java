package com.agentx.ai.core.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Grep Tool - 强大的正则表达式搜索工具
 *
 * @author bigchui
 *
 */
public class GrepTool {

    private static final Logger log = LoggerFactory.getLogger(GrepTool.class);

    private static final int DEFAULT_HEAD_LIMIT = 250;
    private static final boolean DEFAULT_CASE_SENSITIVE = false;
    private static final int DEFAULT_CONTEXT_LINES = 0;

    private final boolean useRipgrep;
    private final Charset charset;

    /**
     * 默认构造函数，自动检测 ripgrep
     */
    public GrepTool() {
        this(StandardCharsets.UTF_8);
    }

    /**
     * 带字符集的构造函数
     *
     * @param charset 文件编码
     */
    public GrepTool(Charset charset) {
        this.charset = charset;
        this.useRipgrep = checkRipgrepAvailable();
        if (useRipgrep) {
            log.info("GrepTool will use ripgrep (rg) for searches");
        } else {
            log.info("GrepTool will use Java native implementation");
        }
    }

    /**
     * 创建 Grep 工具的 ToolCallback 数组（默认配置）
     *
     * 这是一个便捷方法，使用默认配置创建工具实例。
     *
     * @return ToolCallback 数组，包含 grep 工具
     */
    public static ToolCallback[] create() {
        return ToolCallbacks.from(new GrepTool());
    }

    /**
     * 检测系统是否安装了 ripgrep
     *
     * @return 是否可用
     */
    private boolean checkRipgrepAvailable() {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"rg", "--version"});
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /**
     * Grep 搜索主方法
     *
     * @param pattern 正则表达式模式
     * @param path 搜索路径（文件或目录）
     * @param glob 文件类型过滤（如 "*.java"）
     * @param outputMode 输出模式：content/files_with_matches/count
     * @param beforeContext 前置上下文行数（-B）
     * @param afterContext 后置上下文行数（-A）
     * @param ignoreCase 是否忽略大小写（-i）
     * @param headLimit 最大输出行数
     * @param offset 跳过前 N 行
     * @return 格式化的搜索结果
     */
    // @formatter:off
    @Tool(name = "grep", description = """
            基于正则表达式的文件内容搜索工具。

            用法:
            - 始终优先使用本工具进行内容搜索，不要通过 bash 执行 grep 命令
            - 支持完整正则语法（如 "log.*Error"、"function\\\\s+\\\\w+"）
            - 通过 glob 参数按文件类型过滤（如 '*.java'、'*.tsx'）
            - 输出模式: 'content' 显示匹配行，'files_with_matches' 仅显示文件路径，'count' 显示匹配计数
            - beforeContext/afterContext 参数控制上下文行数
            - ignoreCase=true 忽略大小写
            - headLimit/offset 支持分页

            示例:
            - 搜索 Java 文件中的 TODO: pattern='TODO', glob='*.java'
            - 查找函数定义: pattern='function\\\\s+\\\\w+', glob='*.js'
            - 忽略大小写搜索: pattern='error', ignoreCase=true""")
    public String grepContent(
            @ToolParam(description = "【必填】要搜索的正则表达式模式") String pattern,
            @ToolParam(description = "搜索的文件或目录路径（默认为当前目录）", required = false) String path,
            @ToolParam(description = "文件类型过滤（如 '*.java'、'*.tsx'）", required = false) String glob,
            @ToolParam(description = "输出模式: 'content'（匹配行）、'files_with_matches'（文件路径）、'count'（匹配计数）", required = false) String outputMode,
            @ToolParam(description = "匹配行前显示的上下文行数", required = false) Integer beforeContext,
            @ToolParam(description = "匹配行后显示的上下文行数", required = false) Integer afterContext,
            @ToolParam(description = "是否忽略大小写（默认 false）", required = false) Boolean ignoreCase,
            @ToolParam(description = "最大返回结果数", required = false) Integer headLimit,
            @ToolParam(description = "跳过前 N 条结果", required = false) Integer offset) { // @formatter:on

        log.debug("Grep called: pattern={}, path={}, glob={}, mode={}",
            pattern, path, glob, outputMode);

        // 可选参数设置默认值，防止 LLM 不传时 NPE
        if (outputMode == null || outputMode.isEmpty()) outputMode = "content";
        if (path == null || path.isEmpty()) path = ".";
        boolean ignoreCaseVal = Boolean.TRUE.equals(ignoreCase);
        int beforeContextVal = beforeContext != null ? beforeContext : DEFAULT_CONTEXT_LINES;
        int afterContextVal = afterContext != null ? afterContext : DEFAULT_CONTEXT_LINES;
        int headLimitVal = headLimit != null ? headLimit : DEFAULT_HEAD_LIMIT;
        int offsetVal = offset != null ? offset : 0;

        try {
            if (useRipgrep) {
                return searchWithRipgrep(pattern, path, glob, outputMode,
                    beforeContextVal, afterContextVal, ignoreCaseVal, headLimitVal, offsetVal);
            } else {
                return searchWithJava(pattern, path, glob, outputMode,
                    beforeContextVal, afterContextVal, ignoreCaseVal, headLimitVal, offsetVal);
            }
        } catch (Exception e) {
            log.error("Grep search failed", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 使用 ripgrep 进行搜索
     */
    private String searchWithRipgrep(
            String pattern,
            String path,
            String glob,
            String outputMode,
            int beforeContext,
            int afterContext,
            boolean ignoreCase,
            int headLimit,
            int offset) throws IOException, InterruptedException {

        List<String> command = new ArrayList<>();
        command.add("rg");

        // 添加模式
        command.add(pattern);

        // 添加路径
        if (path != null && !path.isEmpty()) {
            command.add(path);
        }

        // 添加 glob 过滤
        if (glob != null && !glob.isEmpty()) {
            command.add("--glob");
            command.add(glob);
        }

        // 上下文参数
        if (beforeContext > 0 || afterContext > 0) {
            command.add("-C");
            command.add(String.valueOf(Math.max(beforeContext, afterContext)));
        }

        // 忽略大小写
        if (ignoreCase) {
            command.add("-i");
        }

        // 行号（默认开启）
        command.add("-n");

        // 输出模式
        switch (outputMode) {
            case "files_with_matches":
                command.add("-l");
                break;
            case "count":
                command.add("-c");
                break;
            case "content":
            default:
                // content 模式不需要额外参数
                break;
        }

        // 执行命令
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // 读取输出
        List<String> lines = new BufferedReader(new InputStreamReader(
            process.getInputStream(), charset))
            .lines()
            .collect(Collectors.toList());

        process.waitFor();

        // 应用 offset 和 headLimit
        List<String> result = lines;
        if (offset > 0 && offset < lines.size()) {
            result = lines.subList(offset, lines.size());
        }
        if (headLimit > 0 && headLimit < result.size()) {
            result = result.subList(0, headLimit);
        }

        return formatResult(result, outputMode);
    }

    /**
     * 使用 Java 原生实现进行搜索
     */
    private String searchWithJava(
            String pattern,
            String path,
            String glob,
            String outputMode,
            int beforeContext,
            int afterContext,
            boolean ignoreCase,
            int headLimit,
            int offset) throws IOException {

        Path searchPath = Paths.get(path);
        if (!Files.exists(searchPath)) {
            return "Error: Path does not exist: " + path;
        }

        // 编译正则表达式
        int flags = ignoreCase ? Pattern.CASE_INSENSITIVE : 0;
        Pattern regex = Pattern.compile(pattern, flags);

        List<String> resultLines = new ArrayList<>();

        // 如果是单个文件
        if (Files.isRegularFile(searchPath)) {
            if (matchesGlob(searchPath.getFileName().toString(), glob)) {
                List<String> fileResults = searchFile(searchPath, regex,
                    outputMode, beforeContext, afterContext);
                resultLines.addAll(fileResults);
            }
        } else if (Files.isDirectory(searchPath)) {
            // 遍历目录
            try (Stream<Path> paths = Files.walk(searchPath)) {
                List<Path> files = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> matchesGlob(p.getFileName().toString(), glob))
                    .collect(Collectors.toList());

                for (Path file : files) {
                    List<String> fileResults = searchFile(file, regex,
                        outputMode, beforeContext, afterContext);
                    resultLines.addAll(fileResults);
                }
            }
        }

        // 应用 offset 和 headLimit
        List<String> result = resultLines;
        if (offset > 0 && offset < result.size()) {
            result = result.subList(offset, result.size());
        }
        if (headLimit > 0 && headLimit < result.size()) {
            result = result.subList(0, headLimit);
        }

        return formatResult(result, outputMode);
    }

    /**
     * 搜索单个文件
     */
    private List<String> searchFile(
            Path file,
            Pattern pattern,
            String outputMode,
            int beforeContext,
            int afterContext) {

        List<String> results = new ArrayList<>();

        try {
            List<String> allLines = readLinesWithFallback(file);
            if (allLines == null) {
                return results;
            }

            if ("files_with_matches".equals(outputMode)) {
                for (String line : allLines) {
                    if (pattern.matcher(line).find()) {
                        results.add(file.toString());
                        return results;
                    }
                }
                return results;
            }

            if ("count".equals(outputMode)) {
                long count = allLines.stream()
                    .filter(line -> pattern.matcher(line).find())
                    .count();
                if (count > 0) {
                    results.add(file.toString() + ":" + count);
                }
                return results;
            }

            // content 模式 - 搜索并添加上下文
            List<MatchInfo> matches = new ArrayList<>();
            for (int i = 0; i < allLines.size(); i++) {
                if (pattern.matcher(allLines.get(i)).find()) {
                    matches.add(new MatchInfo(i + 1, allLines.get(i), file.toString()));
                }
            }

            for (MatchInfo match : matches) {
                int startLine = Math.max(0, match.lineNumber - beforeContext - 1);
                int endLine = Math.min(allLines.size(), match.lineNumber + afterContext);
                for (int i = startLine; i < endLine; i++) {
                    String prefix = (i == match.lineNumber - 1) ? ":" : "-";
                    results.add(String.format("%s%s:%d:%s",
                        match.filePath, prefix, i + 1, allLines.get(i)));
                }
            }
        } catch (IOException e) {
            log.debug("Skipping file due to IO error: {}", file);
        }
        return results;
    }

    /**
     * 使用编码回退机制读取文件所有行。
     * UTF-8 → GBK → ISO-8859-1，确保几乎任何编码的文件都能被读取。
     */
    private List<String> readLinesWithFallback(Path file) throws IOException {
        // 1. 尝试 UTF-8
        try {
            return Files.readAllLines(file, charset);
        } catch (MalformedInputException e) {
            log.debug("File is not {} encoded, trying GBK: {}", charset.name(), file);
        }

        // 2. 尝试 GBK（Windows 中文）
        try {
            return Files.readAllLines(file, Charset.forName("GBK"));
        } catch (Exception e) {
            log.debug("File is not GBK encoded, trying ISO-8859-1: {}", file);
        }

        // 3. ISO-8859-1（单字节编码，不会抛异常）
        try {
            return Files.readAllLines(file, StandardCharsets.ISO_8859_1);
        } catch (Exception e) {
            log.warn("Failed to read file with any encoding: {}", file);
            return null;
        }
    }

    /**
     * 简单的 glob 匹配
     */
    private boolean matchesGlob(String fileName, String glob) {
        if (glob == null || glob.isEmpty() || glob.equals("*")) {
            return true;
        }

        // 将 glob 转换为正则表达式
        String regex = glob
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".");
        return fileName.matches(regex);
    }

    /**
     * 格式化结果
     */
    private String formatResult(List<String> lines, String outputMode) {
        if (lines.isEmpty()) {
            return "No matches found.";
        }

        if ("files_with_matches".equals(outputMode) || "count".equals(outputMode)) {
            return String.join("\n", lines);
        }

        // content 模式
        return String.join("\n", lines);
    }

    /**
     * 匹配信息
     */
    private static class MatchInfo {
        final int lineNumber;
        final String content;
        final String filePath;

        MatchInfo(int lineNumber, String content, String filePath) {
            this.lineNumber = lineNumber;
            this.content = content;
            this.filePath = filePath;
        }
    }
}
