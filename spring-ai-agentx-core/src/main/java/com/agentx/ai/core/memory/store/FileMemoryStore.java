package com.agentx.ai.core.memory.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.agentx.ai.core.memory.model.MemoryItem;
import com.agentx.ai.core.memory.model.MemoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 基于文件系统的记忆存储实现。
 *
 * 记忆以 JSON 文件形式存储在指定目录下，按 userId 分子目录：
 * baseDir/
 * ├── user_001/
 * │   ├── {memoryId}.json
 * │   └── {memoryId}.json
 * └── user_002/
 *     └── {memoryId}.json
 *
 * 适用于需要持久化但不需要数据库的场景。
 *
 * @author bigchui
 * 
 */
public class FileMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(FileMemoryStore.class);
    private static final String FILE_EXTENSION = ".json";

    private final Path baseDir;
    private final ObjectMapper objectMapper;

    /**
     * 创建 FileMemoryStore。
     *
     * @param baseDir 基础目录路径
     */
    public FileMemoryStore(String baseDir) {
        this(Path.of(baseDir));
    }

    /**
     * 创建 FileMemoryStore。
     *
     * @param baseDir 基础目录路径
     */
    public FileMemoryStore(Path baseDir) {
        this.baseDir = baseDir;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        try {
            Files.createDirectories(this.baseDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create memory store directory: " + baseDir, e);
        }
        log.info("FileMemoryStore initialized at: {}", this.baseDir.toAbsolutePath());
    }

    @Override
    public void save(String userId, MemoryItem item) {
        Path userDir = ensureUserDir(userId);
        Path filePath = userDir.resolve(item.getId() + FILE_EXTENSION);
        try {
            objectMapper.writeValue(filePath.toFile(), item);
            log.debug("Saved memory file: {}", filePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save memory: " + filePath, e);
        }
    }

    @Override
    public List<MemoryItem> findByUserId(String userId) {
        Path userDir = getUserDir(userId);
        if (!Files.isDirectory(userDir)) {
            return List.of();
        }

        List<MemoryItem> items = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(userDir, "*" + FILE_EXTENSION)) {
            for (Path file : stream) {
                readItem(file).ifPresent(items::add);
            }
        } catch (IOException e) {
            log.error("Failed to read memories for userId={}", userId, e);
        }
        return items;
    }

    @Override
    public List<MemoryItem> findByUserIdAndType(String userId, MemoryType type) {
        return findByUserId(userId).stream()
                .filter(m -> m.getType() == type)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<MemoryItem> findById(String userId, String memoryId) {
        Path filePath = getUserDir(userId).resolve(memoryId + FILE_EXTENSION);
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        return readItem(filePath);
    }

    @Override
    public void delete(String userId, String memoryId) {
        Path filePath = getUserDir(userId).resolve(memoryId + FILE_EXTENSION);
        try {
            Files.deleteIfExists(filePath);
            log.debug("Deleted memory file: {}", filePath);
        } catch (IOException e) {
            log.error("Failed to delete memory: {}", filePath, e);
        }
    }

    @Override
    public void deleteByUserId(String userId) {
        Path userDir = getUserDir(userId);
        if (!Files.isDirectory(userDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(userDir, "*" + FILE_EXTENSION)) {
            for (Path file : stream) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(userDir);
            log.debug("Deleted all memory files for userId={}", userId);
        } catch (IOException e) {
            log.error("Failed to delete memories for userId={}", userId, e);
        }
    }

    private Path getUserDir(String userId) {
        return baseDir.resolve(sanitizeUserId(userId));
    }

    private Path ensureUserDir(String userId) {
        Path userDir = getUserDir(userId);
        try {
            Files.createDirectories(userDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create user directory: " + userDir, e);
        }
        return userDir;
    }

    private Optional<MemoryItem> readItem(Path file) {
        try {
            return Optional.of(objectMapper.readValue(file.toFile(), MemoryItem.class));
        } catch (IOException e) {
            log.error("Failed to read memory file: {}", file, e);
            return Optional.empty();
        }
    }

    /**
     * 清理 userId 中的特殊字符，防止路径遍历攻击。
     */
    private String sanitizeUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        String sanitized = userId.replaceAll("[^a-zA-Z0-9_.\\-]", "_");
        if (sanitized.equals("..") || sanitized.equals(".")) {
            throw new IllegalArgumentException("Invalid userId: " + userId);
        }
        return sanitized;
    }
}
