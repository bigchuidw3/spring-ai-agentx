package com.agentx.ai.rag.splitter;

import com.agentx.ai.rag.common.ChunkIdGenerator;
import com.agentx.ai.rag.common.ChunkRole;
import com.agentx.ai.rag.common.ContentType;
import com.agentx.ai.rag.common.ImageReferences;
import com.agentx.ai.rag.common.MetadataKeys;
import com.agentx.ai.rag.common.TableReferences;
import com.agentx.ai.rag.common.UuidChunkIdGenerator;
import com.agentx.ai.rag.exception.RagErrorCode;
import com.agentx.ai.rag.exception.RagException;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按 Markdown 标题小节切分，并在小节内部递归细切的分块器。
 *
 * <p>maxHeadingLevel 决定标题边界：小于等于该级别的标题会开启新小节，更深层
 * 标题只作为当前小节内容保留。每个小节内部使用 RecursiveSplitter 生成参与
 * 向量化的子块；enableParentChild=true 时额外输出完整小节作为父块。
 *
 * @author bigchui
 */
public final class HeadingSplitter extends TextSplitter {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*$");

    public static final int DEFAULT_MAX_HEADING_LEVEL = 2;

    private final int maxHeadingLevel;
    private final int chunkSize;
    private final boolean enableParentChild;
    private final boolean stripHeadings;
    private final ChunkIdGenerator chunkIdGenerator;
    private final RecursiveSplitter childSplitter;

    private HeadingSplitter(Builder builder) {
        if (builder.maxHeadingLevel < 1 || builder.maxHeadingLevel > 6) {
            throw new RagException(RagErrorCode.SPLIT_CONFIG_INVALID,
                    "maxHeadingLevel 必须在 1 到 6 之间: " + builder.maxHeadingLevel);
        }
        this.maxHeadingLevel = builder.maxHeadingLevel;
        this.chunkSize = builder.chunkSize;
        this.enableParentChild = builder.enableParentChild;
        this.stripHeadings = builder.stripHeadings;
        this.chunkIdGenerator = builder.chunkIdGenerator == null
                ? new UuidChunkIdGenerator()
                : builder.chunkIdGenerator;
        this.childSplitter = RecursiveSplitter.builder()
                .chunkSize(builder.chunkSize)
                .overlap(builder.overlap)
                .preserveImageRef(builder.preserveImageRef)
                .preserveTableRef(builder.preserveTableRef)
                .separators(builder.separators)
                .chunkIdGenerator(chunkIdGenerator)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public List<Document> split(Document document) {
        Objects.requireNonNull(document, "document");
        if (document.getText() == null || document.getText().isBlank()) {
            return List.of();
        }

        Map<String, Object> baseMetadata = copyMetadata(document.getMetadata());
        AbstractDocumentSplitter.clearChunkLifecycleMetadata(baseMetadata);
        ensureSourceMetadata(baseMetadata, document);
        baseMetadata.putIfAbsent(MetadataKeys.CONTENT_TYPE, ContentType.TEXT.code());

        List<Document> result = new ArrayList<>();
        for (HeadingSection section : parseSections(document.getText())) {
            result.addAll(splitSection(section, baseMetadata, document.getScore()));
        }
        return result;
    }

    @Override
    public List<Document> split(List<Document> documents) {
        if (documents == null) {
            return List.of();
        }
        List<Document> result = new ArrayList<>();
        for (Document document : documents) {
            if (document != null) {
                result.addAll(split(document));
            }
        }
        return result;
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        return split(documents);
    }

    @Override
    protected List<String> splitText(String text) {
        Document document = Document.builder().text(text).build();
        return split(document).stream()
                .map(Document::getText)
                .toList();
    }

    private List<Document> splitSection(HeadingSection section, Map<String, Object> baseMetadata,
                                        Double score) {
        String sectionText = stripHeadings ? section.bodyText() : section.text();
        if (sectionText.isBlank()) {
            return List.of();
        }

        Map<String, Object> sectionMetadata = new LinkedHashMap<>(baseMetadata);
        if (section.heading() != null) {
            sectionMetadata.put(MetadataKeys.HEADING_LEVEL, section.level());
            sectionMetadata.put(MetadataKeys.HEADING, section.heading().text());
            sectionMetadata.put(MetadataKeys.HEADING_PATH, section.headingPath());
        }

        Document.Builder sectionBuilder = Document.builder()
                .id(chunkIdGenerator.nextId())
                .text(sectionText)
                .metadata(new LinkedHashMap<>(sectionMetadata));
        if (score != null) {
            sectionBuilder.score(score);
        }
        List<Document> children = childSplitter.split(sectionBuilder.build());
        if (children.isEmpty()) {
            return List.of();
        }
        if (!enableParentChild) {
            return children;
        }

        String parentChunkId = chunkIdGenerator.nextId();
        Map<String, Object> parentMetadata = new LinkedHashMap<>(sectionMetadata);
        parentMetadata.put(MetadataKeys.CHUNK_ID, parentChunkId);
        parentMetadata.put(MetadataKeys.CHUNK_ROLE, ChunkRole.PARENT.code());
        parentMetadata.put(MetadataKeys.SKIP_EMBEDDING, 1);

        List<Document> result = new ArrayList<>(children.size() + 1);
        result.add(buildDocument(sectionText, parentChunkId, parentMetadata, score));
        for (Document child : children) {
            result.add(markChildChunk(child, parentChunkId));
        }
        return result;
    }

    private Document markChildChunk(Document source, String parentChunkId) {
        Map<String, Object> metadata = copyMetadata(source.getMetadata());
        String childChunkId = nonBlank(asString(metadata.get(MetadataKeys.CHUNK_ID)))
                ? metadata.get(MetadataKeys.CHUNK_ID).toString()
                : chunkIdGenerator.nextId();

        metadata.put(MetadataKeys.CHUNK_ID, childChunkId);
        metadata.put(MetadataKeys.PARENT_CHUNK_ID, parentChunkId);
        metadata.put(MetadataKeys.CHUNK_ROLE, ChunkRole.CHILD.code());
        metadata.remove(MetadataKeys.SKIP_EMBEDDING);
        return buildDocument(source.getText(), childChunkId, metadata, source.getScore());
    }

    private Document buildDocument(String text, String id, Map<String, Object> metadata, Double score) {
        applyContentMetadata(metadata, text, chunkSize);
        Document.Builder builder = Document.builder()
                .id(id)
                .text(text)
                .metadata(metadata);
        if (score != null) {
            builder.score(score);
        }
        return builder.build();
    }

    private void applyContentMetadata(Map<String, Object> metadata, String text, int chunkSize) {
        AbstractDocumentSplitter.applyImageMetadata(metadata, text, chunkSize);
        AbstractDocumentSplitter.applyTableMetadata(metadata, text, chunkSize);
    }

    private List<HeadingSection> parseSections(String text) {
        List<HeadingSection> sections = new ArrayList<>();
        List<HeadingMatch> headingStack = new ArrayList<>();
        HeadingMatch currentHeading = null;
        StringBuilder currentText = new StringBuilder();
        boolean inCodeBlock = false;
        String codeFence = "";

        for (String line : text.lines().toList()) {
            String trimmed = line.trim();
            if (!inCodeBlock && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))) {
                inCodeBlock = true;
                codeFence = trimmed.substring(0, 3);
                currentText.append(line).append('\n');
                continue;
            }
            if (inCodeBlock && trimmed.startsWith(codeFence)) {
                inCodeBlock = false;
                codeFence = "";
                currentText.append(line).append('\n');
                continue;
            }

            HeadingMatch match = inCodeBlock ? null : parseHeading(trimmed);
            if (match != null) {
                addSection(sections, currentHeading, headingPath(headingStack), currentText);
                currentHeading = match;
                updateHeadingStack(headingStack, match);
                currentText = new StringBuilder(line).append('\n');
            } else {
                currentText.append(line).append('\n');
            }
        }
        addSection(sections, currentHeading, headingPath(headingStack), currentText);
        return sections;
    }

    private void addSection(List<HeadingSection> sections, HeadingMatch heading,
                            List<String> headingPath, StringBuilder text) {
        if (text.length() == 0) {
            return;
        }
        sections.add(new HeadingSection(
                heading == null ? 0 : heading.level(),
                heading,
                headingPath == null ? List.of() : List.copyOf(headingPath),
                text.toString()));
    }

    private void updateHeadingStack(List<HeadingMatch> headingStack, HeadingMatch heading) {
        while (!headingStack.isEmpty() && headingStack.getLast().level() >= heading.level()) {
            headingStack.removeLast();
        }
        headingStack.add(heading);
    }

    private List<String> headingPath(List<HeadingMatch> headingStack) {
        if (headingStack.isEmpty()) {
            return List.of();
        }
        return headingStack.stream()
                .map(HeadingMatch::text)
                .toList();
    }

    private HeadingMatch parseHeading(String line) {
        Matcher matcher = HEADING_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return null;
        }
        int level = matcher.group(1).length();
        if (level > maxHeadingLevel) {
            return null;
        }
        return new HeadingMatch(level, matcher.group(2).trim());
    }

    private void ensureSourceMetadata(Map<String, Object> metadata, Document source) {
        String fileName = asString(metadata.get(MetadataKeys.FILE_NAME));
        if (isBlank(fileName)) {
            fileName = isBlank(source.getId()) ? chunkIdGenerator.nextId() : source.getId();
        }
        metadata.put(MetadataKeys.FILE_NAME, fileName);
    }

    private static Map<String, Object> copyMetadata(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private record HeadingSection(int level, HeadingMatch heading, List<String> headingPath, String text) {

        private String bodyText() {
            if (heading == null) {
                return text;
            }
            int firstNewline = text.indexOf('\n');
            return firstNewline < 0 ? "" : text.substring(firstNewline + 1);
        }
    }

    private record HeadingMatch(int level, String text) {
    }

    public static final class Builder {

        private int maxHeadingLevel = DEFAULT_MAX_HEADING_LEVEL;
        private boolean enableParentChild;
        private boolean stripHeadings;
        private int chunkSize = AbstractDocumentSplitter.DEFAULT_CHUNK_SIZE;
        private int overlap = AbstractDocumentSplitter.DEFAULT_OVERLAP;
        private boolean preserveImageRef = true;
        private boolean preserveTableRef = true;
        private List<String> separators = RecursiveSplitter.DEFAULT_SEPARATORS;
        private ChunkIdGenerator chunkIdGenerator = new UuidChunkIdGenerator();

        public Builder maxHeadingLevel(int maxHeadingLevel) {
            this.maxHeadingLevel = maxHeadingLevel;
            return this;
        }

        public Builder enableParentChild(boolean enableParentChild) {
            this.enableParentChild = enableParentChild;
            return this;
        }

        public Builder stripHeadings(boolean stripHeadings) {
            this.stripHeadings = stripHeadings;
            return this;
        }

        public Builder chunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
            return this;
        }

        public Builder overlap(int overlap) {
            this.overlap = overlap;
            return this;
        }

        public Builder preserveImageRef(boolean preserveImageRef) {
            this.preserveImageRef = preserveImageRef;
            return this;
        }

        public Builder preserveTableRef(boolean preserveTableRef) {
            this.preserveTableRef = preserveTableRef;
            return this;
        }

        public Builder separators(List<String> separators) {
            this.separators = separators;
            return this;
        }

        public Builder separators(String... separators) {
            this.separators = separators == null ? null : List.of(separators);
            return this;
        }

        public Builder chunkIdGenerator(ChunkIdGenerator chunkIdGenerator) {
            this.chunkIdGenerator = chunkIdGenerator;
            return this;
        }

        public HeadingSplitter build() {
            return new HeadingSplitter(this);
        }
    }
}
