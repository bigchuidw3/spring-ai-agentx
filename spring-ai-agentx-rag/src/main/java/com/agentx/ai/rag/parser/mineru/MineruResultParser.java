package com.agentx.ai.rag.parser.mineru;

import com.agentx.ai.rag.common.ContentType;
import com.agentx.ai.rag.parser.image.ImageMediaTypes;
import com.agentx.ai.rag.parser.ParsedBlock;
import com.agentx.ai.rag.exception.RagErrorCode;
import com.agentx.ai.rag.exception.RagException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * MinerU 结果包解析器。
 *
 * <p>从 full.md 保留完整 Markdown 上下文，同时按 content_list.json 提取图片二进制，
 * 供后续多模态入库使用。
 *
 * @author bigchui
 */
final class MineruResultParser {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".jp2", ".webp", ".gif", ".bmp");

    private final ObjectMapper objectMapper;

    MineruResultParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    List<ParsedBlock> parse(byte[] zipContent) {
        Objects.requireNonNull(zipContent, "zipContent");
        Map<String, byte[]> entries = readZipEntries(zipContent);

        String markdown = readMarkdown(entries);
        JsonNode contentList = readContentList(entries);
        List<ParsedBlock> blocks = new ArrayList<>();
        blocks.add(ParsedBlock.text(markdown));
        blocks.addAll(readImageBlocks(entries, contentList));
        return blocks;
    }

    private Map<String, byte[]> readZipEntries(byte[] zipContent) {
        try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(zipContent))) {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                entries.put(normalizePath(entry.getName()), zipInputStream.readAllBytes());
            }
            return entries;
        } catch (IOException e) {
            throw new RagException(RagErrorCode.DOCUMENT_PARSE_FAILED,
                    "读取 MinerU 结果压缩包失败", e);
        }
    }

    private String readMarkdown(Map<String, byte[]> entries) {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (isMarkdownFile(entry.getKey())) {
                return new String(entry.getValue(), StandardCharsets.UTF_8);
            }
        }
        throw new RagException(RagErrorCode.DOCUMENT_PARSE_FAILED,
                "MinerU 结果压缩包缺少 full.md");
    }

    private JsonNode readContentList(Map<String, byte[]> entries) {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (entry.getKey().endsWith("_content_list.json")) {
                try {
                    return objectMapper.readTree(entry.getValue());
                } catch (IOException e) {
                    throw new RagException(RagErrorCode.DOCUMENT_PARSE_FAILED,
                            "读取 MinerU content_list.json 失败", e);
                }
            }
        }
        return null;
    }

    private List<ParsedBlock> readImageBlocks(Map<String, byte[]> entries, JsonNode contentList) {
        if (contentList != null && contentList.isArray()) {
            List<ParsedBlock> images = new ArrayList<>();
            for (int i = 0; i < contentList.size(); i++) {
                JsonNode item = contentList.get(i);
                String type = item.path("type").asText("");
                if ("image".equals(type) || "chart".equals(type)) {
                    images.add(imageBlock(item, entries, i));
                }
            }
            return images;
        }
        return readImageEntries(entries);
    }

    private List<ParsedBlock> readImageEntries(Map<String, byte[]> entries) {
        List<ParsedBlock> images = new ArrayList<>();
        int index = 0;
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String path = entry.getKey();
            if (path.startsWith("images/") && hasImageExtension(path)) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("mineruType", "image");
                metadata.put("sourceImagePath", path);
                metadata.put("mediaType", ImageMediaTypes.fromFileName(path));
                metadata.put("mineruBlockIndex", index++);
                images.add(new ParsedBlock(ContentType.IMAGE, "", entry.getValue(), metadata));
            }
        }
        return images;
    }

    private ParsedBlock imageBlock(JsonNode item, Map<String, byte[]> entries, int index) {
        String imagePath = normalizePath(item.path("img_path").asText(""));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mineruType", item.path("type").asText());
        metadata.put("sourceImagePath", imagePath);
        metadata.put("mediaType", ImageMediaTypes.fromFileName(imagePath));
        metadata.put("mineruBlockIndex", index);
        if (item.hasNonNull("page_idx")) {
            metadata.put("pageIdx", item.path("page_idx").asInt());
        }
        if (item.path("bbox").isArray() && !item.path("bbox").isEmpty()) {
            metadata.put("bbox", toIntegerList(item.path("bbox")));
        }
        if (item.hasNonNull("sub_type")) {
            metadata.put("subType", item.path("sub_type").asText());
        }

        String description = firstNonBlank(
                joinText(item.path("image_caption")),
                joinText(item.path("chart_caption")),
                item.path("content").asText(null));
        byte[] binary = entries.get(imagePath);
        if (binary == null) {
            throw new RagException(RagErrorCode.DOCUMENT_PARSE_FAILED,
                    "MinerU 结果压缩包缺少图片: " + imagePath);
        }
        return new ParsedBlock(
                ContentType.IMAGE,
                description == null ? "" : description,
                binary,
                metadata);
    }

    private List<Integer> toIntegerList(JsonNode values) {
        List<Integer> result = new ArrayList<>(values.size());
        for (JsonNode value : values) {
            result.add(value.asInt());
        }
        return List.copyOf(result);
    }

    private String joinText(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isArray()) {
            List<String> values = new ArrayList<>(value.size());
            for (JsonNode item : value) {
                if (!item.isNull() && !item.asText().isBlank()) {
                    values.add(item.asText());
                }
            }
            return values.isEmpty() ? null : String.join(" ", values);
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private boolean isMarkdownFile(String path) {
        return path.equals("full.md") || path.endsWith("/full.md");
    }

    private boolean hasImageExtension(String path) {
        int dotIndex = path.lastIndexOf('.');
        if (dotIndex < 0) {
            return false;
        }
        return IMAGE_EXTENSIONS.contains(path.substring(dotIndex).toLowerCase(Locale.ROOT));
    }

    private String normalizePath(String path) {
        String normalized = path.replace('\\', '/');
        return normalized.startsWith("./") ? normalized.substring(2) : normalized;
    }
}
