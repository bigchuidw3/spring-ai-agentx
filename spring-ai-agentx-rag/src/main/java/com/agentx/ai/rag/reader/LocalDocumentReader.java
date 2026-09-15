package com.agentx.ai.rag.reader;

import com.agentx.ai.rag.exception.RagErrorCode;
import com.agentx.ai.rag.exception.RagException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 本地文件读取器。
 *
 * @author bigchui
 */
public final class LocalDocumentReader implements DocumentReader {

    private static final DateTimeFormatter LAST_MODIFIED_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path path;

    public LocalDocumentReader(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    @Override
    public RawDocument read() {
        validate(path);
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("sourceType", "local");
            metadata.put("sourceUri", path.toUri().toString());
            metadata.put("fileSize", Files.size(path));
            LocalDateTime lastModified = Files.getLastModifiedTime(path)
                    .toInstant()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime();
            metadata.put("lastModified", lastModified.format(LAST_MODIFIED_FORMATTER));

            return new RawDocument(
                    path.getFileName().toString(),
                    Files.readAllBytes(path),
                    metadata);
        } catch (IOException e) {
            throw new RagException(RagErrorCode.DOCUMENT_READ_FAILED,
                    "读取本地文件失败: " + path, e);
        }
    }

    private static void validate(Path path) {
        if (!Files.exists(path)) {
            throw new RagException(RagErrorCode.DOCUMENT_READ_FAILED, "文件不存在: " + path);
        }
        if (!Files.isRegularFile(path)) {
            throw new RagException(RagErrorCode.DOCUMENT_READ_FAILED, "目标不是普通文件: " + path);
        }
        if (!Files.isReadable(path)) {
            throw new RagException(RagErrorCode.DOCUMENT_READ_FAILED, "文件不可读: " + path);
        }
    }
}
