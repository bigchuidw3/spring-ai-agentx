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

    /**
     * 判断指定 code point 是否为图片/表格占位符。
     */
    boolean isPlaceholder(int codePoint) {
        return placeholders.containsKey(codePoint);
    }

    /**
     * 返回单个 code point 对真实文本长度的贡献：普通字符为 1，占位符按其原始内容
     * code point 数计。图片/表格描述不再被折叠成 1 个字符参与长度计算。
     */
    int codePointWeight(int codePoint) {
        String original = placeholders.get(codePoint);
        return original == null ? 1 : original.codePointCount(0, original.length());
    }

    /**
     * 受保护文本的真实 code point 长度（占位符按原始内容长度计）。
     */
    int realLength(String protectedText) {
        int total = 0;
        int[] codePoints = protectedText.codePoints().toArray();
        for (int codePoint : codePoints) {
            total += codePointWeight(codePoint);
        }
        return total;
    }

    /**
     * 返回受保护文本的一个后缀：从末尾往回累计真实长度，直到不超过 maxLength，
     * 边界始终停在整 code point 上（不拆占位符）。用于 mergePieces 的 overlap 前缀，
     * 保证「overlap 前缀 + 下一段」的真实长度不超过 chunkSize。
     */
    String suffixByRealLength(String protectedText, int maxLength) {
        if (protectedText == null || protectedText.isEmpty() || maxLength <= 0) {
            return "";
        }
        int[] codePoints = protectedText.codePoints().toArray();
        int start = codePoints.length;
        int weight = 0;
        while (start > 0 && weight + codePointWeight(codePoints[start - 1]) <= maxLength) {
            start--;
            weight += codePointWeight(codePoints[start]);
        }
        return start >= codePoints.length ? "" : new String(codePoints, start, codePoints.length - start);
    }

    /**
     * 按真实长度、原子边界切分受保护文本，返回仍为占位符编码的片段（由调用方 restore）。
     *
     * <p>图片/表格占位符计入其原始内容长度且不可拆分；贪心打包时，遇到下一个原子单元
     * 会超限则先封当前片段、用该单元开新片段，因此每个片段最多允许一个超限的原子单元
     * （单个单元本身就大于 chunkSize 时独占一个片段）。
     */
    List<String> splitProtectedByLength(String protectedText, int chunkSize, int overlap) {
        if (protectedText == null || protectedText.isEmpty()) {
            return List.of();
        }
        int[] codePoints = protectedText.codePoints().toArray();
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < codePoints.length) {
            int end = start;
            int weight = 0;
            while (end < codePoints.length) {
                int codePoint = codePoints[end];
                int unitWeight = codePointWeight(codePoint);
                if (isPlaceholder(codePoint) && weight > 0 && weight + unitWeight > chunkSize) {
                    // 原子单元会撑爆非空片段：封口，用它开新片段
                    break;
                }
                weight += unitWeight;
                end++;
                if (weight >= chunkSize) {
                    break;
                }
            }
            if (end == start) {
                // 单个原子单元大于 chunkSize：允许其独占一个片段（最多超一个）
                end = start + 1;
            }
            chunks.add(new String(codePoints, start, end - start));
            if (end >= codePoints.length) {
                break;
            }
            int nextStart = overlapStart(codePoints, end, start, overlap);
            start = nextStart > start ? nextStart : end;
        }
        return chunks;
    }

    /**
     * 从上一片段尾部按真实长度回退 overlap 个 code point 作为下一片段前缀，
     * 回退时始终在原子边界（整 code point）停留，且不会越过 floor。
     */
    private int overlapStart(int[] codePoints, int end, int floor, int overlap) {
        if (overlap <= 0) {
            return end;
        }
        int pos = end;
        int weight = 0;
        while (pos > floor) {
            pos--;
            weight += codePointWeight(codePoints[pos]);
            if (weight >= overlap) {
                break;
            }
        }
        return pos;
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
