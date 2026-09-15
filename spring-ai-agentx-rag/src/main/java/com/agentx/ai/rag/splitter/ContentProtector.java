package com.agentx.ai.rag.splitter;

import com.agentx.ai.rag.common.ImageReferences;
import com.agentx.ai.rag.common.TableReferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分块过程中的内容保护器，统一管理图片引用和 HTML 表格的原子性保护。
 *
 * 保护顺序：先表格后图片（表格是更大的容器，可能包含图片引用）；
 * 恢复时按反序：先图片后表格。每类内容使用独立的 Supplementary Private Use
 * 区段作为占位符，确保不互相冲突。
 *
 * @author bigchui
 */
final class ContentProtector {

    private static final int TABLE_PLACEHOLDER_BASE = 0x100000;
    private static final int IMAGE_PLACEHOLDER_BASE = 0xF0000;

    private final Map<Integer, String> placeholders = new HashMap<>();
    private String protectedText;

    private ContentProtector() {
    }

    /**
     * 保护文本中的图片引用和/或 HTML 表格。
     *
     * @param text            原始文本
     * @param preserveImageRef 是否保护图片引用
     * @param preserveTableRef 是否保护 HTML 表格
     * @return 保护器实例
     */
    static ContentProtector protect(String text, boolean preserveImageRef, boolean preserveTableRef) {
        ContentProtector protector = new ContentProtector();
        String current = text;

        if (preserveTableRef) {
            current = protector.protectTables(current);
        }
        if (preserveImageRef) {
            current = protector.protectImages(current);
        }

        protector.protectedText = current;
        return protector;
    }

    boolean hasProtectedContent() {
        return !placeholders.isEmpty();
    }

    String protectedText() {
        return protectedText;
    }

    /**
     * 恢复 chunk 中的所有占位符为原始内容。
     *
     * <p>恢复顺序与保护相反：先图片后表格，确保嵌套关系正确还原。
     */
    String restore(String chunk) {
        if (placeholders.isEmpty()) {
            return chunk;
        }
        StringBuilder result = new StringBuilder(chunk.length());
        chunk.codePoints().forEach(codePoint -> {
            String original = placeholders.get(codePoint);
            result.append(original == null
                    ? new String(Character.toChars(codePoint))
                    : original);
        });
        return result.toString();
    }

    private String protectTables(String text) {
        List<TableReferences.TableReference> tables = TableReferences.find(text);
        if (tables.isEmpty()) {
            return text;
        }

        int base = availableBase(text, TABLE_PLACEHOLDER_BASE, tables.size());
        List<String> markers = new ArrayList<>(tables.size());
        for (int i = 0; i < tables.size(); i++) {
            String marker = new String(Character.toChars(base + i));
            markers.add(marker);
            placeholders.put(base + i, tables.get(i).token());
        }

        int[] cursor = new int[1];
        return TableReferences.replace(text, ref -> markers.get(cursor[0]++));
    }

    private String protectImages(String text) {
        List<ImageReferences.ImageReference> images = ImageReferences.find(text);
        if (images.isEmpty()) {
            return text;
        }

        int base = availableBase(text, IMAGE_PLACEHOLDER_BASE, images.size());
        List<String> markers = new ArrayList<>(images.size());
        for (int i = 0; i < images.size(); i++) {
            String marker = new String(Character.toChars(base + i));
            markers.add(marker);
            placeholders.put(base + i, images.get(i).token());
        }

        int[] cursor = new int[1];
        return ImageReferences.replace(text, ref -> markers.get(cursor[0]++));
    }

    private static int availableBase(String text, int start, int count) {
        int base = start;
        while (containsAnyCodePoint(text, base, count)) {
            base += count + 1;
        }
        return base;
    }

    private static boolean containsAnyCodePoint(String text, int start, int count) {
        return text.codePoints().anyMatch(cp -> cp >= start && cp < start + count);
    }
}
