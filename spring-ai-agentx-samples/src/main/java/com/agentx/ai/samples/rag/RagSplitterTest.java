package com.agentx.ai.samples.rag;

import com.agentx.ai.rag.common.ChunkRole;
import com.agentx.ai.rag.common.MetadataKeys;
import com.agentx.ai.rag.parser.DocumentParser;
import com.agentx.ai.rag.reader.LocalDocumentReader;
import com.agentx.ai.rag.reader.RawDocument;
import com.agentx.ai.rag.parser.tika.TikaDocumentParser;
import com.agentx.ai.rag.splitter.HeadingSplitter;
import com.agentx.ai.rag.splitter.RecursiveSplitter;
import com.agentx.ai.rag.splitter.SlidingWindowSplitter;
import com.agentx.ai.samples.TestConfig;
import org.springframework.ai.document.Document;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;

/**
 * RAG 文档解析与三种分块策略测试。
 *
 * <p>修改 testNumber 切换测试场景，直接运行 main 方法即可：
 * <pre>
 * 1: SlidingWindowSplitter — 固定窗口 + overlap
 * 2: RecursiveSplitter — 按默认中英文分隔符递归切分
 * 3: HeadingSplitter — Markdown 标题边界 + 递归子块 + 父子模式开关
 * </pre>
 *
 * @author bigchui
 */
public class RagSplitterTest {

    private static final Path DEFAULT_SAMPLE_FILE = Path.of(
            "C:\\Users\\Lenovo\\Desktop\\notes\\new\\tc\\25-执行验证工具-台词.md");

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));

        Path sampleFile = args.length > 0 ? Path.of(args[0]) : DEFAULT_SAMPLE_FILE;
        List<Document> sourceDocuments = parse(sampleFile);
        int testNumber = 3;
        TestConfig.printTestHeader("RAG Splitter Test #" + testNumber);

        switch (testNumber) {
            case 1 -> testSlidingWindowSplitter(sourceDocuments);
            case 2 -> testRecursiveSplitter(sourceDocuments);
            case 3 -> testHeadingSplitter(sourceDocuments);
            default -> System.out.println("Unknown test number: " + testNumber);
        }
    }

    private static List<Document> parse(Path sampleFile) throws Exception {
        System.out.println("========== Tika 文档解析 ==========");
        DocumentParser parser = new TikaDocumentParser();
        RawDocument rawDocument = new LocalDocumentReader(sampleFile).read();
        System.out.printf("文件=%s, bytes=%d%n", rawDocument.fileName(), rawDocument.content().length);
        return parser.parseToDocuments(rawDocument);
    }

    static void testSlidingWindowSplitter(List<Document> sourceDocuments) {
        List<Document> chunks = SlidingWindowSplitter.builder()
                .chunkSize(180)
                .overlap(20)
                .build()
                .split(sourceDocuments);

        printDocuments("SlidingWindowSplitter", chunks);
        validateCommonChunks(chunks, sourceDocuments, 180);
        System.out.printf("校验通过：滑动窗口分块 %d 个%n%n", chunks.size());
    }

    static void testRecursiveSplitter(List<Document> sourceDocuments) {
        List<Document> chunks = RecursiveSplitter.builder()
                .chunkSize(180)
                .overlap(20)
                .build()
                .split(sourceDocuments);

        printDocuments("RecursiveSplitter", chunks);
        validateCommonChunks(chunks, sourceDocuments, 180);
        System.out.printf("校验通过：递归分块 %d 个%n%n", chunks.size());
    }

    static void testHeadingSplitter(List<Document> sourceDocuments) {
        List<Document> childOnlyChunks = headingChildOnlyChunks(sourceDocuments);
        testHeadingParentChild(sourceDocuments, childOnlyChunks);
    }

    private static List<Document> headingChildOnlyChunks(List<Document> sourceDocuments) {
        List<Document> chunks = HeadingSplitter.builder()
                .maxHeadingLevel(2)
                .chunkSize(180)
                .overlap(20)
                .enableParentChild(false)
                .build()
                .split(sourceDocuments);

        printDocuments("HeadingSplitter enableParentChild=false", chunks);
        for (Document chunk : chunks) {
            requireState(chunk.getMetadata().get(MetadataKeys.PARENT_CHUNK_ID) == null,
                    "子块模式不应输出 parentChunkId");
            requireState(chunk.getMetadata().get(MetadataKeys.CHUNK_ROLE) == null,
                    "子块模式不应输出 chunkRole");
            requireState(chunk.getMetadata().get(MetadataKeys.SKIP_EMBEDDING) == null,
                    "子块模式不应输出 skipEmbedding");
            validateHeadingPath(chunk);
        }
        System.out.printf("校验通过：仅输出 %d 个可向量化子块%n%n", chunks.size());
        return chunks;
    }

    private static void testHeadingParentChild(List<Document> sourceDocuments,
                                               List<Document> childOnlyChunks) {
        List<Document> chunks = HeadingSplitter.builder()
                .maxHeadingLevel(2)
                .chunkSize(180)
                .overlap(20)
                .enableParentChild(true)
                .build()
                .split(sourceDocuments);

        printDocuments("HeadingSplitter enableParentChild=true", chunks);

        Set<String> parentChunkIds = new HashSet<>();
        Map<String, Integer> childCounts = new java.util.HashMap<>();
        for (Document chunk : chunks) {
            Object role = chunk.getMetadata().get(MetadataKeys.CHUNK_ROLE);
            if (ChunkRole.PARENT.code().equals(role)) {
                parentChunkIds.add(chunk.getId());
            } else if (ChunkRole.CHILD.code().equals(role)) {
                String parentId = (String) chunk.getMetadata().get(MetadataKeys.PARENT_CHUNK_ID);
                childCounts.merge(parentId, 1, Integer::sum);
            }
        }

        String expected = sourceText(sourceDocuments).replaceAll("\\s+", " ").trim();
        String actual = chunks.stream()
                .filter(c -> ChunkRole.PARENT.code().equals(c.getMetadata().get(MetadataKeys.CHUNK_ROLE)))
                .map(Document::getText)
                .reduce("", (a, b) -> a + "\n" + b)
                .replaceAll("\\s+", " ").trim();
        requireState(expected.contains(actual),
                "父块合并后必须覆盖源文档全部内容");
        requireState(childCounts.values().stream().mapToInt(Integer::intValue).sum() == childOnlyChunks.size(),
                "父子模式不应改变子块数量");

        requireState(!parentChunkIds.isEmpty(), "必须输出父块");
        for (Map.Entry<String, Integer> entry : childCounts.entrySet()) {
            requireState(parentChunkIds.contains(entry.getKey()),
                    "子块 parentChunkId 不存在: " + entry.getKey());
            requireState(entry.getValue() > 0, "父块必须至少有一个子块");
        }

        System.out.printf("校验通过：父块=%d, 子块=%d, 总块=%d%n",
                parentChunkIds.size(), childCounts.values().stream().mapToInt(Integer::intValue).sum(), chunks.size());
        childCounts.forEach((parentId, count) ->
                System.out.printf("  parent=%s, childCount=%d%n", parentId, count));
        System.out.println();
    }

    private static String sourceText(List<Document> documents) {
        StringBuilder text = new StringBuilder();
        for (Document document : documents) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(document.getText());
        }
        return text.toString();
    }

    private static void validateHeadingPath(Document chunk) {
        Map<String, Object> metadata = chunk.getMetadata();
        if (!metadata.containsKey(MetadataKeys.HEADING_PATH)) {
            return;
        }

        Object value = metadata.get(MetadataKeys.HEADING_PATH);
        requireState(value instanceof List, "headingPath 必须是字符串数组");
        List<?> headingPath = (List<?>) value;
        requireState(!headingPath.isEmpty(), "headingPath 不能为空数组");
        for (Object heading : headingPath) {
            requireState(heading instanceof String && !heading.toString().isBlank(),
                    "headingPath 数组元素必须是非空字符串");
        }
        requireState(Objects.equals(headingPath.getLast(), metadata.get(MetadataKeys.HEADING)),
                "headingPath 最后一级必须等于当前标题");
    }

    private static void validateCommonChunks(List<Document> chunks, List<Document> sourceDocuments,
                                             int chunkSize) {
        requireState(!chunks.isEmpty(), "必须产出分块结果");
        for (Document chunk : chunks) {
            requireState(chunk.getId() != null && !chunk.getId().isBlank(), "每个块必须有 id");
            requireState(chunk.getText() != null && !chunk.getText().isBlank(), "每个块必须有文本");
            requireState(chunk.getMetadata().get(MetadataKeys.CHUNK_ID) != null, "必须有 chunkId");
            requireState(chunk.getMetadata().get(MetadataKeys.CHUNK_GROUP_ID) != null, "必须有 chunkGroupId");
            requireState(chunk.getMetadata().get(MetadataKeys.CHUNK_INDEX) != null, "必须有 chunkIndex");
            requireState(chunk.getMetadata().get(MetadataKeys.DOCUMENT_ID) != null, "必须有 documentId");
        }
    }

    private static void printDocuments(String title, List<Document> documents) {
        System.out.println("---- " + title + " (" + documents.size() + " 块) ----");
        for (int i = 0; i < documents.size(); i++) {
            Document document = documents.get(i);
            Map<String, Object> metadata = document.getMetadata();
            Object parentChunkId = metadata.get(MetadataKeys.PARENT_CHUNK_ID);
            Object skipEmbedding = metadata.get(MetadataKeys.SKIP_EMBEDDING);
            Object chunkRole = metadata.get(MetadataKeys.CHUNK_ROLE);
            String role = chunkRole == null ? "chunk" : chunkRole.toString();

            System.out.printf("[%03d] role=%s, id=%s%n", i, role, document.getId());
            System.out.printf("      text=%s%n", document.getText());
            System.out.printf("      chunkId=%s, documentId=%s, fileName=%s, contentType=%s%n",
                    metadata.get(MetadataKeys.CHUNK_ID),
                    metadata.get(MetadataKeys.DOCUMENT_ID),
                    metadata.get(MetadataKeys.FILE_NAME),
                    metadata.get(MetadataKeys.CONTENT_TYPE));
            printIfPresent("      parentChunkId", parentChunkId);
            printIfPresent("      chunkRole", chunkRole);
            printIfPresent("      chunkGroupId", metadata.get(MetadataKeys.CHUNK_GROUP_ID));
            printIfPresent("      chunkIndex", metadata.get(MetadataKeys.CHUNK_INDEX));
            printIfPresent("      chunkTotal", metadata.get(MetadataKeys.CHUNK_TOTAL));
            printIfPresent("      headingLevel", metadata.get(MetadataKeys.HEADING_LEVEL));
            printIfPresent("      heading", metadata.get(MetadataKeys.HEADING));
            printIfPresent("      headingPath", metadata.get(MetadataKeys.HEADING_PATH));
            printIfPresent("      skipEmbedding", skipEmbedding);
            printIfPresent("      containsImg", metadata.get(MetadataKeys.CONTAINS_IMG));
            printIfPresent("      containsTable", metadata.get(MetadataKeys.CONTAINS_TABLE));
            printIfPresent("      oversizedTableRef", metadata.get(MetadataKeys.OVERSIZED_TABLE_REF));
        }
    }

    private static void printIfPresent(String label, Object value) {
        if (value != null) {
            System.out.printf("%s=%s%n", label, value);
        }
    }

    private static String preview(String value, int maxLength) {
        String normalized = normalizeWhitespace(value);
        if (normalized.codePointCount(0, normalized.length()) <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, normalized.offsetByCodePoints(0, maxLength)) + "...";
    }

    private static String normalizeWhitespace(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private static void requireState(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
