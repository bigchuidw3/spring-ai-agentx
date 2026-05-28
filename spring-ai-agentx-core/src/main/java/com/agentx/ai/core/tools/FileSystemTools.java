package com.agentx.ai.core.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * FileSystemTools - File system tools for Spring AI
 *
 * Provides file system operations as Spring AI tools:
 * - read_file: Read file content with pagination (offset/limit)
 * - write_file: Write new files only
 * - edit_file: Edit files via string replacement (supports replace_all)
 * - glob_files: Glob pattern file matching
 * - list_files: List directory contents
 *
 * Features:
 * - Builder pattern configuration
 * - Path security checks (prevents directory traversal)
 * - Virtual mode support
 * - Based on Claude Code tool system
 *
 */
public class FileSystemTools {

    private static final Logger logger = LoggerFactory.getLogger(FileSystemTools.class);

    private static final int DEFAULT_MAX_FILE_SIZE_MB = 10;
    private static final int DEFAULT_LINE_LIMIT = 500;
    private static final int MAX_LINE_LENGTH = 10000;
    private static final int LINE_NUMBER_WIDTH = 6;
    private static final String EMPTY_CONTENT_WARNING = "System reminder: File exists but has empty contents";

    private final Path cwd;
    private final boolean virtualMode;
    private final long maxFileSizeBytes;

    /**
     * 默认构造函数，使用当前工作目录。
     */
    public FileSystemTools() {
        this(null, false, DEFAULT_MAX_FILE_SIZE_MB);
    }

    /**
     * 带自定义根目录的构造函数。
     *
     * @param rootDir 文件操作的根目录（可选）
     * @param virtualMode 为 true 时，将传入路径视为 cwd 下的虚拟绝对路径
     * @param maxFileSizeMb 读取操作的最大文件大小（MB）
     */
    public FileSystemTools(String rootDir, boolean virtualMode, int maxFileSizeMb) {
        this.cwd = rootDir != null ? Paths.get(rootDir).toAbsolutePath().normalize() : Paths.get("").toAbsolutePath();
        this.virtualMode = virtualMode;
        this.maxFileSizeBytes = (long) maxFileSizeMb * 1024L * 1024L;
        logger.debug("FileSystemTools initialized: cwd={}, virtualMode={}, maxFileSize={}MB",
                cwd, virtualMode, maxFileSizeMb);
    }

    /**
     * 解析文件路径并进行安全检查。
     *
     * @param path 输入路径
     * @return 解析后的安全路径
     * @throws IllegalArgumentException 如果路径不合法或存在安全问题
     */
    private Path resolvePath(String path) throws IllegalArgumentException {
        if (path == null || path.trim().isEmpty()) {
            return cwd;
        }

        if (virtualMode) {
            // 虚拟模式：将路径视为 cwd 下的虚拟绝对路径
            String vpath = path.startsWith("/") ? path : "/" + path;
            if (vpath.contains("..") || vpath.startsWith("~")) {
                throw new IllegalArgumentException("Path traversal not allowed: " + path);
            }
            Path full = cwd.resolve(vpath.substring(1)).normalize();
            if (!full.startsWith(cwd)) {
                throw new IllegalArgumentException("Path " + full + " outside root directory: " + cwd);
            }
            return full;
        }

        // 非虚拟模式：支持绝对路径和相对路径
        Path resolvedPath = Paths.get(path);
        if (resolvedPath.isAbsolute()) {
            return resolvedPath.normalize();
        }
        return cwd.resolve(resolvedPath).normalize();
    }

    // @formatter:off
    @Tool(name = "read_file", description = """
            读取文件系统中的文件内容。

            用法:
            - filePath 支持绝对路径和相对路径
            - 相对路径（如 'file.txt' 或 './subdir/file.txt'）基于当前工作目录解析
            - 默认从文件开头读取最多 500 行
            - **大文件务必使用分页参数**：先用 offset=0, limit=100 快速浏览结构，再按需读取更多内容
            - 超过 10000 字符的行会被截断
            - 返回结果带行号（从 1 开始），格式如 cat -n
            - 建议先使用 list_files 工具确认文件路径，再使用本工具读取
            - 可以在同一次响应中批量读取多个文件""")
    public String readFile(
            @ToolParam(description = "【必填】要读取的文件路径，禁止传空。支持绝对路径或相对路径。例如: 'pom.xml'、'./src/main/java/App.java'") String filePath,
            @ToolParam(description = "起始行偏移量（默认: 0）", required = false) Integer offset,
            @ToolParam(description = "最大读取行数（默认: 500）", required = false) Integer limit,
            @ToolParam(description = "图片编码格式（可选）", required = false) String imageFormat) { // @formatter:on

        try {
            Path resolvedPath = resolvePath(filePath);
            logger.debug("Reading file: {}", resolvedPath);
            return readFileContent(resolvedPath, offset, limit, true);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid path for read_file: {}", e.getMessage());
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            logger.error("Error reading file '{}': {}", filePath, e.getMessage(), e);
            return "Error reading file '" + filePath + "': " + e.getMessage();
        }
    }

    /**
     * 读取文件内容的核心逻辑（可被其他类复用）。
     *
     * @param filePath 文件路径
     * @param offset 起始行偏移量（null 表示 0）
     * @param limit 最大行数（null 表示 500）
     * @param checkEmpty 是否检查空内容
     * @return 带行号的格式化文件内容，或错误消息
     */
    private String readFileContent(Path filePath, Integer offset, Integer limit, boolean checkEmpty) {
        try {
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath, LinkOption.NOFOLLOW_LINKS)) {
                return "Error: File '" + filePath + "' not found";
            }

            // 检查文件大小
            long fileSize = Files.size(filePath);
            if (fileSize > maxFileSizeBytes) {
                return "Error: File size (" + (fileSize / 1024 / 1024) + "MB) exceeds maximum allowed size (" +
                       (maxFileSizeBytes / 1024 / 1024) + "MB)";
            }

            // 尝试多种编码读取文件（处理编码问题）
            String content = readStringWithFallback(filePath);

            if (checkEmpty) {
                if (content == null || content.trim().isEmpty()) {
                    return EMPTY_CONTENT_WARNING;
                }
            }

            String[] lines = content.split("\n", -1);
            // 移除末尾的空行（如果有）
            if (lines.length > 0 && lines[lines.length - 1].isEmpty()) {
                lines = Arrays.copyOf(lines, lines.length - 1);
            }

            int startIdx = offset != null ? offset : 0;
            int endIdx = Math.min(startIdx + (limit != null ? limit : DEFAULT_LINE_LIMIT), lines.length);

            if (startIdx >= lines.length) {
                return "Error: Line offset " + startIdx + " exceeds file length (" + lines.length + " lines)";
            }

            if (startIdx < 0) {
                return "Error: Line offset cannot be negative";
            }

            String[] selectedLines = Arrays.copyOfRange(lines, startIdx, endIdx);
            return formatContentWithLineNumbers(selectedLines, startIdx + 1);
        } catch (IOException e) {
            logger.error("IO error reading file '{}': {}", filePath, e.getMessage(), e);
            return "Error reading file '" + filePath + "': " + e.getMessage();
        }
    }

    /**
     * 使用编码回退机制读取文件内容。
     *
     * 尝试多种编码读取文件，处理不同编码格式的文件：
     * 1. 首先尝试 UTF-8（标准编码）
     * 2. 如果失败，尝试 GBK（Windows 中文环境）
     * 3. 如果还失败，尝试 ISO-8859-1（不会抛异常，但可能显示乱码）
     *
     * @param filePath 文件路径
     * @return 文件内容字符串
     * @throws IOException 如果所有编码尝试都失败
     */
    private String readStringWithFallback(Path filePath) throws IOException {
        // 尝试 UTF-8
        try {
            return Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (java.nio.charset.MalformedInputException e) {
            logger.debug("File is not UTF-8 encoded, trying GBK: {}", filePath);
        }

        // 尝试 GBK (Windows 中文)
        try {
            return Files.readString(filePath, java.nio.charset.Charset.forName("GBK"));
        } catch (Exception e) {
            logger.debug("File is not GBK encoded, trying ISO-8859-1: {}", filePath);
        }

        // 最后尝试 ISO-8859-1（单字节编码，不会抛出异常）
        byte[] bytes = Files.readAllBytes(filePath);
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    /**
     * 使用行号格式化内容（cat -n 格式）。
     * 处理长行时将其分割成多个块。
     *
     * @param lines 行数组
     * @param startLine 起始行号
     * @return 格式化后的内容
     */
    private String formatContentWithLineNumbers(String[] lines, int startLine) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNum = i + startLine;

            if (line.length() <= MAX_LINE_LENGTH) {
                result.append(String.format("%" + LINE_NUMBER_WIDTH + "d\t%s\n", lineNum, line));
            } else {
                // 将长行分割成多个块，带续行标记
                int numChunks = (line.length() + MAX_LINE_LENGTH - 1) / MAX_LINE_LENGTH;
                for (int chunkIdx = 0; chunkIdx < numChunks; chunkIdx++) {
                    int start = chunkIdx * MAX_LINE_LENGTH;
                    int end = Math.min(start + MAX_LINE_LENGTH, line.length());
                    String chunk = line.substring(start, end);
                    if (chunkIdx == 0) {
                        result.append(String.format("%" + LINE_NUMBER_WIDTH + "d\t%s\n", lineNum, chunk));
                    } else {
                        String continuationMarker = lineNum + "." + chunkIdx;
                        result.append(String.format("%" + LINE_NUMBER_WIDTH + "s\t%s\n", continuationMarker, chunk));
                    }
                }
            }
        }
        // 移除末尾的换行符
        if (!result.isEmpty() && result.charAt(result.length() - 1) == '\n') {
            result.setLength(result.length() - 1);
        }
        return result.toString();
    }

    // @formatter:off
    @Tool(name = "write_file", description = """
            创建新文件并写入内容。

            用法:
            - filePath 支持绝对路径和相对路径，【必须明确指定文件名】
            - 相对路径（如 'output.txt' 或 './result/data.json'）基于当前工作目录解析
            - 只能创建新文件，如果文件已存在会报错。如需修改已有文件，请使用 edit_file 工具
            - 父目录不存在时会自动创建
            - 支持写入代码、多行文本等任何内容，优先使用本工具而非 bash 创建文件

            示例:
            - filePath='result.txt', content='分析结果: ...'
            - filePath='./output/report.md', content='# 报告\\n内容...'
            - filePath='C:\\\\Users\\\\test\\\\data.json', content='{"key": "value"}'""")
    public String writeFile(
            @ToolParam(description = "【必填】要写入的文件路径，必须包含文件名（含扩展名），禁止传空。支持绝对路径或相对路径。例如: 'output.txt'、'./result/data.json'、'D:\\\\files\\\\report.md'") String filePath,
            @ToolParam(description = "【必填】要写入文件的内容") String content) { // @formatter:on

        if (filePath == null || filePath.trim().isEmpty()) {
            return "Error: filePath 不能为空，必须指定文件名（含扩展名）。例如: 'result.txt'、'./output/report.md'";
        }

        try {
            Path resolvedPath = resolvePath(filePath);
            logger.debug("Writing file: {}", resolvedPath);
            return writeFileContent(resolvedPath, content);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid path for write_file: {}", e.getMessage());
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            logger.error("Error writing file '{}': {}", filePath, e.getMessage(), e);
            return "Error writing file '" + filePath + "': " + e.getMessage();
        }
    }

    /**
     * 写入文件内容的核心逻辑（可被其他类复用）。
     *
     * @param filePath 文件路径
     * @param content 要写入的内容（null 将写入空内容）
     * @return 成功消息或错误消息
     */
    private String writeFileContent(Path filePath, String content) {
        try {
            // 如果需要，创建父目录
            Path parent = filePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
                logger.debug("Created parent directories: {}", parent);
            }

            // 写入内容到文件（已存在则覆盖）
            byte[] contentBytes = content != null ? content.getBytes(StandardCharsets.UTF_8) : new byte[0];
            Files.write(filePath, contentBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            logger.info("Successfully created file: {} ({} bytes)", filePath, contentBytes.length);
            return "Successfully created file: " + filePath;
        } catch (IOException e) {
            logger.error("IO error writing file '{}': {}", filePath, e.getMessage(), e);
            return "Error writing file '" + filePath + "': " + e.getMessage();
        }
    }

    // @formatter:off
    @Tool(name = "edit_file", description = """
            通过字符串替换编辑已有文件。

            用法:
            - filePath 支持绝对路径和相对路径
            - 编辑前必须先用 read_file 读取文件内容
            - 替换时会严格匹配缩进（空格/tab），请确保 oldString 与文件中的内容完全一致
            - 如果 oldString 在文件中不唯一，编辑会失败。请提供更多上下文使其唯一，或设置 replaceAll=true 替换所有匹配
            - 设置 replaceAll=true 可批量替换文件中所有匹配的字符串""")
    public String editFile(
            @ToolParam(description = "【必填】要编辑的文件路径，禁止传空。支持绝对路径或相对路径") String filePath,
            @ToolParam(description = "【必填】要被替换的原始文本，必须与文件内容完全匹配（包括缩进）") String oldString,
            @ToolParam(description = "【必填】替换后的新文本，必须与 oldString 不同") String newString,
            @ToolParam(description = "是否替换所有匹配项（默认 false，仅替换第一个匹配）", required = false) Boolean replaceAll) { // @formatter:on

        try {
            Path resolvedPath = resolvePath(filePath);
            boolean replaceAllFlag = Boolean.TRUE.equals(replaceAll);
            logger.debug("Editing file: {}, replaceAll={}", resolvedPath, replaceAllFlag);
            return editFileContent(resolvedPath, oldString, newString, replaceAllFlag);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid path for edit_file: {}", e.getMessage());
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            logger.error("Error editing file '{}': {}", filePath, e.getMessage(), e);
            return "Error editing file '" + filePath + "': " + e.getMessage();
        }
    }

    /**
     * 编辑文件内容的核心逻辑（可被其他类复用）。
     *
     * @param filePath 文件路径
     * @param oldString 要替换的文本
     * @param newString 替换后的文本
     * @param replaceAll 是否替换所有出现位置
     * @return 成功消息（带替换次数）或错误消息
     */
    private String editFileContent(Path filePath, String oldString, String newString, boolean replaceAll) {
        try {
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath, LinkOption.NOFOLLOW_LINKS)) {
                return "Error: File '" + filePath + "' not found";
            }

            if (oldString == null || oldString.isEmpty()) {
                return "Error: old_string cannot be null or empty";
            }

            if (oldString.equals(newString)) {
                return "Error: new_string must be different from old_string";
            }

            String content = Files.readString(filePath);

            // 统计出现次数
            int occurrences = countOccurrences(content, oldString);

            if (occurrences == 0) {
                return "Error: String not found in file: '" + oldString + "'";
            }

            // 如果不是 replace_all，检查唯一性
            if (!replaceAll && occurrences > 1) {
                return "Error: String '" + oldString + "' appears " + occurrences +
                       " times in file. Use replaceAll=true to replace all instances, " +
                       "or provide a more specific string with surrounding context.";
            }

            // 执行替换
            String newContent;
            if (replaceAll) {
                newContent = content.replace(oldString, newString);
            } else {
                // 仅替换第一个出现位置（使用字面字符串匹配，而非正则）
                int replaceIndex = content.indexOf(oldString);
                if (replaceIndex != -1) {
                    newContent = content.substring(0, replaceIndex) + newString +
                                 content.substring(replaceIndex + oldString.length());
                } else {
                    // 不应到达此处，因为已经检查过存在性
                    newContent = content;
                }
            }

            // 将修改后的内容写回
            Files.writeString(filePath, newContent, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            logger.info("Successfully edited file: {} (replaced {} occurrence(s))", filePath, occurrences);
            return String.format("Successfully edited file: %s (replaced %d occurrence(s))", filePath, occurrences);
        } catch (IOException e) {
            logger.error("IO error editing file '{}': {}", filePath, e.getMessage(), e);
            return "Error editing file '" + filePath + "': " + e.getMessage();
        }
    }

    /**
     * 统计子串在内容中的出现次数。
     *
     * @param content 内容
     * @param search 搜索字符串
     * @return 出现次数
     */
    private int countOccurrences(String content, String search) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(search, index)) != -1) {
            count++;
            index += search.length();
        }
        return count;
    }

    // @formatter:off
    @Tool(name = "list_files", description = """
            列出指定目录下的所有文件和子目录（非递归）。

            用法:
            - path 支持绝对路径和相对路径，如 '.' 或 './src'
            - 返回目录中的文件和子目录列表（不会递归子目录）
            - 目录路径以 '/' 结尾
            - 建议在使用 read_file 或 edit_file 之前先使用本工具确认文件路径

            示例:
            - path='.' 列出当前目录
            - path='./src/main/java' 列出指定目录""")
    public String listFiles(
            @ToolParam(description = "要列出的目录路径，不传则默认列出当前目录。支持绝对路径或相对路径。例如: '.'、'./src'、'D:\\\\project'") String path) { // @formatter:on

        try {
            Path dirPath = resolvePath(path);
            if (!Files.exists(dirPath) || !Files.isDirectory(dirPath)) {
                return "Error: Directory not found: " + path;
            }

            logger.debug("Listing directory: {}", dirPath);
            List<FileInfo> results = listFilesContent(dirPath);

            // 格式化输出
            StringBuilder result = new StringBuilder();
            for (FileInfo info : results) {
                result.append(info.getPath());
                if (info.getSize() != null) {
                    result.append(" (").append(info.getSize()).append(" bytes)");
                }
                if (info.getModifiedAt() != null) {
                    result.append(" [").append(info.getModifiedAt()).append("]");
                }
                result.append("\n");
            }

            String output = !result.isEmpty() ? result.toString().trim() : "Directory is empty";
            logger.debug("Listed {} items in {}", results.size(), dirPath);
            return output;
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid path for list_files: {}", e.getMessage());
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            logger.error("Error listing directory '{}': {}", path, e.getMessage(), e);
            return "Error listing directory '" + path + "': " + e.getMessage();
        }
    }

    /**
     * 列出目录中文件和目录的核心逻辑（可被其他类复用）。
     *
     * @param dirPath 目录路径
     * @return FileInfo 对象列表
     */
    private List<FileInfo> listFilesContent(Path dirPath) {
        List<FileInfo> results = new ArrayList<>();

        try {
            if (!Files.exists(dirPath) || !Files.isDirectory(dirPath)) {
                return results;
            }

            try (Stream<Path> paths = Files.list(dirPath)) {
                List<Path> pathList = paths.collect(Collectors.toList());
                for (Path childPath : pathList) {
                    try {
                        boolean isFile = Files.isRegularFile(childPath, LinkOption.NOFOLLOW_LINKS);
                        boolean isDir = Files.isDirectory(childPath, LinkOption.NOFOLLOW_LINKS);

                        String absPath = childPath.toString();

                        if (isFile) {
                            try {
                                BasicFileAttributes attrs = Files.readAttributes(
                                        childPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                                results.add(new FileInfo(
                                        absPath,
                                        false,
                                        attrs.size(),
                                        formatTimestamp(attrs.lastModifiedTime().toInstant())
                                ));
                            } catch (IOException e) {
                                results.add(new FileInfo(absPath, false, null, null));
                            }
                        } else if (isDir) {
                            try {
                                BasicFileAttributes attrs = Files.readAttributes(
                                        childPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                                results.add(new FileInfo(
                                        absPath + "/",
                                        true,
                                        0L,
                                        formatTimestamp(attrs.lastModifiedTime().toInstant())
                                ));
                            } catch (IOException e) {
                                results.add(new FileInfo(absPath + "/", true, null, null));
                            }
                        }
                    } catch (Exception ignored) {
                        // 跳过无法访问的文件
                        logger.debug("Skipping inaccessible file: {}", childPath);
                    }
                }
            }

            // 按路径排序以保持确定性顺序
            results.sort(Comparator.comparing(FileInfo::getPath));
            return results;
        } catch (Exception e) {
            logger.error("Error listing directory content: {}", e.getMessage(), e);
            return results;
        }
    }

    /**
     * 将时间戳格式化为 ISO 偏移日期时间字符串。
     *
     * @param instant 时间戳
     * @return 格式化的时间戳字符串
     */
    private String formatTimestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    // @formatter:off
    @Tool(name = "glob_files", description = """
            通过 Glob 模式查找文件。

            用法:
            - 支持标准 Glob 语法: *（任意字符）、**（任意目录层级）、?（单个字符）
            - 相对模式基于当前工作目录解析
            - 返回匹配文件的绝对路径列表

            示例:
            - '**/*.java' 递归查找所有 Java 文件
            - '*.txt' 查找当前目录下所有文本文件
            - './src/**/*.xml' 递归查找 src 目录下所有 XML 文件
            - '**/*Test*.java' 查找文件名包含 Test 的 Java 文件""")
    public String globFiles(
            @ToolParam(description = "【必填】Glob 匹配模式。例如: '**/*.java'、'*.txt'、'./src/**/*.xml'") String pattern) { // @formatter:on

        try {
            logger.debug("Globbing files with pattern: {}", pattern);
            List<String> matchedFiles = globFilesContent(pattern);

            if (matchedFiles.isEmpty()) {
                return "No files found matching pattern: " + pattern;
            }

            String result = String.join("\n", matchedFiles);
            logger.debug("Glob found {} files matching: {}", matchedFiles.size(), pattern);
            return result;
        } catch (Exception e) {
            logger.error("Error globbing files with pattern '{}': {}", pattern, e.getMessage(), e);
            return "Error searching for files: " + e.getMessage();
        }
    }

    /**
     * 使用 Glob 模式匹配文件的核心逻辑。
     *
     * @param pattern Glob 模式
     * @return 匹配的文件路径列表
     */
    private List<String> globFilesContent(String pattern) {
        List<String> matchedFiles = new ArrayList<>();

        try {
            final Path searchRoot;
            String searchPattern = pattern;

            if (pattern.startsWith("/") || (pattern.length() >= 2 && pattern.charAt(1) == ':')) {
                // 绝对路径中可能混有 glob 通配符，需要拆分出基础目录和 glob 模式
                int globStart = findGlobStart(pattern);
                if (globStart > 0) {
                    String basePath = pattern.substring(0, globStart);
                    searchPattern = pattern.substring(globStart);
                    searchRoot = Paths.get(basePath).normalize();
                } else {
                    // 整个路径不含通配符，作为普通路径处理
                    searchRoot = Paths.get(pattern).normalize();
                    searchPattern = "*";
                }
            } else {
                searchRoot = cwd;
            }

            if (!Files.exists(searchRoot)) {
                logger.warn("Glob search root does not exist: {}", searchRoot);
                return matchedFiles;
            }

            // PathMatcher 要求 glob 模式使用正斜杠
            String normalizedPattern = searchPattern.replace('\\', '/');
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + normalizedPattern);

            // If recursive mode (contains **), search subdirectories
            if (normalizedPattern.contains("**")) {
                try (Stream<Path> paths = Files.walk(searchRoot)) {
                    paths.filter(Files::isRegularFile)
                         .filter(path -> {
                             Path relativePath = searchRoot.relativize(path);
                             return matcher.matches(relativePath) || matcher.matches(path);
                         })
                         .forEach(path -> matchedFiles.add(path.toString()));
                }
            } else {
                // Non-recursive mode, only search current directory
                try (Stream<Path> paths = Files.list(searchRoot)) {
                    paths.filter(Files::isRegularFile)
                         .filter(path -> matcher.matches(path.getFileName()))
                         .forEach(path -> matchedFiles.add(path.toString()));
                }
            }

            // 排序以保持确定性顺序
            Collections.sort(matchedFiles);
            return matchedFiles;
        } catch (IOException e) {
            logger.error("IO error during glob search: {}", e.getMessage(), e);
            return matchedFiles;
        }
    }

    /**
     * 找到路径中第一个 glob 通配符的位置（在最后一个路径分隔符之前）。
     * 例如 "C:\Users\test\**\*" 返回 "C:\Users\test" 后面的分隔符位置。
     */
    private static int findGlobStart(String pattern) {
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*' || c == '?' || c == '[' || c == '{') {
                // 回退到最近的路径分隔符，确保基础目录是完整路径
                String sub = pattern.substring(0, i);
                int lastSep = Math.max(sub.lastIndexOf('/'), sub.lastIndexOf('\\'));
                return lastSep >= 0 ? lastSep + 1 : 0;
            }
        }
        return -1;
    }

    /**
     * 创建文件系统工具的 ToolCallback 数组（默认配置）
     *
     * 这是一个便捷方法，使用默认配置创建工具实例。
     *
     * @return ToolCallback 数组，包含 read_file, write_file, edit_file, list_files, glob_files 工具
     */
    public static ToolCallback[] create() {
        return ToolCallbacks.from(new FileSystemTools());
    }

    /**
     * 创建 Builder 实例。
     *
     * @return Builder 对象
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder 模式用于配置 FileSystemTools。
     */
    public static class Builder {
        private String rootDir;
        private boolean virtualMode = false;
        private int maxFileSizeMb = DEFAULT_MAX_FILE_SIZE_MB;

        /**
         * 设置根目录。
         *
         * @param rootDir 根目录路径
         * @return this
         */
        public Builder rootDir(String rootDir) {
            this.rootDir = rootDir;
            return this;
        }

        /**
         * 设置虚拟模式。
         *
         * @param virtualMode 是否启用虚拟模式
         * @return this
         */
        public Builder virtualMode(boolean virtualMode) {
            this.virtualMode = virtualMode;
            return this;
        }

        /**
         * 设置最大文件大小。
         *
         * @param maxFileSizeMb 最大文件大小（MB）
         * @return this
         */
        public Builder maxFileSizeMb(int maxFileSizeMb) {
            this.maxFileSizeMb = maxFileSizeMb;
            return this;
        }

        /**
         * 构建 FileSystemTools 实例。
         *
         * @return FileSystemTools 实例
         */
        public FileSystemTools build() {
            return new FileSystemTools(rootDir, virtualMode, maxFileSizeMb);
        }
    }

    /**
     * 文件信息类，用于结构化返回文件列表信息。
     */
    public static class FileInfo {
        private final String path;
        private final Boolean isDir;
        private final Long size;
        private final String modifiedAt;

        public FileInfo(String path, Boolean isDir, Long size, String modifiedAt) {
            this.path = path;
            this.isDir = isDir;
            this.size = size;
            this.modifiedAt = modifiedAt;
        }

        public String getPath() {
            return path;
        }

        public Boolean getIsDir() {
            return isDir;
        }

        public Long getSize() {
            return size;
        }

        public String getModifiedAt() {
            return modifiedAt;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(path);
            if (size != null) {
                sb.append(" (").append(size).append(" bytes)");
            }
            if (modifiedAt != null) {
                sb.append(" [").append(modifiedAt).append("]");
            }
            return sb.toString();
        }
    }
}
