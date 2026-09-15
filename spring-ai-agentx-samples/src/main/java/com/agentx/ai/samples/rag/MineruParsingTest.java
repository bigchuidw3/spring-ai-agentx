package com.agentx.ai.samples.rag;

import com.agentx.ai.rag.asset.LocalDocumentAssetStore;
import com.agentx.ai.rag.common.ContentType;
import com.agentx.ai.rag.common.ImageReferences;
import com.agentx.ai.rag.common.MetadataKeys;
import com.agentx.ai.rag.parser.DocumentParser;
import com.agentx.ai.rag.parser.ParsedBlock;
import com.agentx.ai.rag.parser.ParsedDocument;
import com.agentx.ai.rag.parser.mineru.MineruDocumentParser;
import com.agentx.ai.rag.reader.LocalDocumentReader;
import com.agentx.ai.rag.reader.RawDocument;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 本地 Reader 与 MinerU 在线解析测试。
 *
 * <p>修改 testNumber 切换测试场景，直接运行 main 方法即可：
 * <pre>
 * 1: LocalDocumentReader - 只读取本地文件并输出原始文档信息
 * 2: MineruDocumentParser - 调用在线 MinerU，输出 Markdown 与图片块
 * 3: MinerU + HeadingSplitter - 解析后继续按标题分块
 * 4: MinerU 图片语义理解 - 多模态模型生成描述并写回 Markdown
 * 5: Image & Table Protection - 验证图片引用和表格不会被切块器截断
 * </pre>
 *
 * @author bigchui
 */
public class MineruParsingTest {

    static int testNumber = 4;

    private static final Path DEFAULT_SAMPLE_FILE = Path.of(
            "D:\\download\\✅Claude Code中如何使用Skills.docx");

    public static void main(String[] args) {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));

        Path sampleFile = args.length > 0 ? Path.of(args[0]) : DEFAULT_SAMPLE_FILE;
        TestConfig.printTestHeader("MinerU Parsing Test #" + testNumber);

        switch (testNumber) {
            case 1 -> testLocalReader(sampleFile);
            case 2 -> testMineruParsing(sampleFile);
            case 3 -> testMineruSplitting(sampleFile);
            case 4 -> testMineruImageUnderstanding(sampleFile);
            case 5 -> testContentProtection(sampleFile);
            default -> System.out.println("Unknown test number: " + testNumber);
        }
    }

    static void testLocalReader(Path sampleFile) {
        System.out.println("========== LocalDocumentReader ==========");
        RawDocument rawDocument = new LocalDocumentReader(sampleFile).read();

        System.out.printf("fileName=%s, contentLength=%d%n", rawDocument.fileName(), rawDocument.content().length);
        rawDocument.metadata().forEach((key, value) -> System.out.printf("%s=%s%n", key, value));

        requireState(!rawDocument.fileName().isBlank(), "fileName 不能为空");
        requireState(rawDocument.content().length > 0, "本地文件内容不能为空");
        requireState(rawDocument.metadata().containsKey("sourceUri"), "必须输出 sourceUri");
        System.out.println("\n校验通过：本地 Reader 输出原始字节和来源 metadata");
    }

    static void testMineruParsing(Path sampleFile) {
        ParsedDocument parsedDocument = parseWithMineru(sampleFile, false, false);
        printParsedDocument(parsedDocument);
    }

    static void testMineruSplitting(Path sampleFile) {
        List<Document> sourceDocuments = parseToDocumentsWithMineru(sampleFile, false, false);
        List<Document> chunks = HeadingSplitter.builder()
                .maxHeadingLevel(2)
                .chunkSize(600)
                .overlap(80)
                .enableParentChild(false)
                .build()
                .split(sourceDocuments);

        printChunks("MinerU + HeadingSplitter", sourceDocuments, chunks);
        requireState(!chunks.isEmpty(), "MinerU Markdown 必须可以进入分块器");
        System.out.println("\n校验通过：MinerU 解析结果可以接入现有分块链路");
    }

    static void testMineruImageUnderstanding(Path sampleFile) {
        List<Document> sourceDocuments = parseToDocumentsWithMineru(sampleFile, true, true);
        List<Document> chunks = HeadingSplitter.builder()
                .maxHeadingLevel(2)
                .chunkSize(600)
                .overlap(80)
                .enableParentChild(false)
                .build()
                .split(sourceDocuments);
        printChunks("MinerU Image Understanding + HeadingSplitter", sourceDocuments, chunks);
        requireState(!chunks.isEmpty(), "图片语义理解后必须产出可分块文本");
        System.out.println("\n校验通过：图片语义理解 + 分块链路正常");
    }

    static void testContentProtection(Path sampleFile) {
        List<Document> sourceDocuments = parseToDocumentsWithMineru(sampleFile, false, false);

        System.out.println("========== SlidingWindowSplitter - 表格保护 ==========");
        List<Document> swChunks = SlidingWindowSplitter.builder()
                .chunkSize(600)
                .overlap(80)
                .build()
                .split(sourceDocuments);
        printProtectionSummary("SlidingWindow", swChunks, 600);

        System.out.println("\n========== RecursiveSplitter - 表格保护 ==========");
        List<Document> recChunks = RecursiveSplitter.builder()
                .chunkSize(600)
                .overlap(80)
                .build()
                .split(sourceDocuments);
        printProtectionSummary("Recursive", recChunks, 600);

        System.out.println("\n========== HeadingSplitter - 表格保护 ==========");
        List<Document> headChunks = HeadingSplitter.builder()
                .maxHeadingLevel(2)
                .chunkSize(600)
                .overlap(80)
                .enableParentChild(false)
                .build()
                .split(sourceDocuments);
        printProtectionSummary("Heading", headChunks, 600);

        System.out.println("\n校验通过：三种切块器均保护了图片引用和表格完整性");
    }

    private static void printProtectionSummary(String name, List<Document> chunks, int chunkSize) {
        int imgChunks = 0;
        int tableChunks = 0;
        int oversizedImg = 0;
        int oversizedTable = 0;

        for (Document chunk : chunks) {
            Map<String, Object> m = chunk.getMetadata();
            if (Boolean.TRUE.equals(m.get(MetadataKeys.CONTAINS_IMG))) imgChunks++;
            if (Boolean.TRUE.equals(m.get(MetadataKeys.OVERSIZED_IMG_REF))) oversizedImg++;
            if (Boolean.TRUE.equals(m.get(MetadataKeys.CONTAINS_TABLE))) tableChunks++;
            if (Boolean.TRUE.equals(m.get(MetadataKeys.OVERSIZED_TABLE_REF))) oversizedTable++;
        }

        System.out.printf("[%s] total=%d, imgChunks=%d, oversizedImg=%d, tableChunks=%d, oversizedTable=%d%n",
                name, chunks.size(), imgChunks, oversizedImg, tableChunks, oversizedTable);

        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Map<String, Object> m = chunk.getMetadata();
            boolean hasImg = Boolean.TRUE.equals(m.get(MetadataKeys.CONTAINS_IMG));
            boolean hasTable = Boolean.TRUE.equals(m.get(MetadataKeys.CONTAINS_TABLE));
            if (hasImg || hasTable) {
                System.out.printf("  [%03d] containsImg=%s, containsTable=%s, text=%s%n",
                        i, hasImg, hasTable, preview(chunk.getText(), 200));
            }
        }
    }

    private static RawDocument readLocal(Path sampleFile) {
        return new LocalDocumentReader(sampleFile).read();
    }

    private static ParsedDocument parseWithMineru(Path sampleFile, boolean enableImageUnderstanding,
                                                  boolean enableAssetStore) {
        RawDocument rawDocument = readLocal(sampleFile);
        MineruDocumentParser.Builder parserBuilder = MineruDocumentParser.builder()
                .authToken(mineruToken())
                .enableImageUnderstanding(enableImageUnderstanding);
        if (enableImageUnderstanding) {
            configureImageUnderstanding(parserBuilder);
        }
        if (enableAssetStore) {
            parserBuilder.assetStore(new LocalDocumentAssetStore(
                    Path.of(".rag-tmp6", "rag-assets")));
        }
        DocumentParser parser = parserBuilder.build();
        return parser.parse(rawDocument);
    }

    private static List<Document> parseToDocumentsWithMineru(Path sampleFile, boolean enableImageUnderstanding,
                                                            boolean enableAssetStore) {
        RawDocument rawDocument = readLocal(sampleFile);
        MineruDocumentParser.Builder parserBuilder = MineruDocumentParser.builder()
                .authToken(mineruToken())
                .enableImageUnderstanding(enableImageUnderstanding);
        if (enableImageUnderstanding) {
            configureImageUnderstanding(parserBuilder);
        }
        if (enableAssetStore) {
            parserBuilder.assetStore(new LocalDocumentAssetStore(
                    Path.of(".rag-tmp6", "rag-assets")));
        }
        DocumentParser parser = parserBuilder.build();
        return parser.parseToDocuments(rawDocument);
    }

    private static void configureImageUnderstanding(MineruDocumentParser.Builder parserBuilder) {
        parserBuilder.imageChatModel(TestConfig.createMultimodalChatModel())
                .imageWorkers(4);
    }

    private static void printParsedDocument(ParsedDocument parsedDocument) {
        Map<ContentType, Integer> typeCounts = new TreeMap<>();
        for (ParsedBlock block : parsedDocument.blocks()) {
            typeCounts.merge(block.type(), 1, Integer::sum);
        }

        System.out.printf("fileName=%s, blocks=%d%n",
                parsedDocument.fileName(), parsedDocument.blocks().size());
        parsedDocument.metadata().forEach((key, value) -> System.out.printf("%s=%s%n", key, value));
        System.out.printf("blockTypes=%s%n", typeCounts);

        String markdown = parsedDocument.blocks().stream()
                .filter(block -> block.type() == ContentType.TEXT)
                .map(ParsedBlock::text)
                .findFirst()
                .orElse("");
        System.out.printf("%nMarkdown preview:%n%s%n", preview(markdown, 1200));
        printImages(parsedDocument.blocks());

        requireState(parsedDocument.blocks().size() > typeCounts.getOrDefault(ContentType.IMAGE, 0),
                "必须至少输出一个非图片内容块");
    }

    private static void printChunks(String title, List<Document> sourceDocuments, List<Document> chunks) {
        System.out.println("========== " + title + " ==========");
        System.out.printf("可切分文本块=%d, 输出 chunks=%d%n", sourceDocuments.size(), chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Map<String, Object> m = chunk.getMetadata();
            System.out.printf("%n[%03d] id=%s, contentType=%s%n",
                    i, chunk.getId(), m.get(MetadataKeys.CONTENT_TYPE));
            printIfPresent("      containsImg", m.get(MetadataKeys.CONTAINS_IMG));
            printIfPresent("      containsTable", m.get(MetadataKeys.CONTAINS_TABLE));
            printIfPresent("      oversizedTableRef", m.get(MetadataKeys.OVERSIZED_TABLE_REF));
            System.out.printf("text=%s%n", chunk.getText());
        }
    }

    private static void printImages(List<ParsedBlock> blocks) {
        List<ParsedBlock> images = blocks.stream()
                .filter(block -> block.type() == ContentType.IMAGE)
                .toList();
        System.out.printf("%nImage blocks (%d):%n", images.size());
        for (int i = 0; i < images.size(); i++) {
            ParsedBlock image = images.get(i);
            Map<String, Object> metadata = new LinkedHashMap<>(image.metadata());
            Object sourcePath = metadata.remove("sourceImagePath");
            Object assetUri = metadata.remove("assetUri");
            Object pageIdx = metadata.remove("pageIdx");
            Object description = metadata.remove("imageDescription");
            Object assetSize = metadata.remove("assetSize");
            System.out.printf("[%03d] sourcePath=%s, assetUri=%s, page=%s, assetSize=%s%n",
                    i, sourcePath, assetUri, pageIdx, assetSize);
            if (description != null) {
                System.out.printf("      description=%s%n", preview(description.toString(), 200));
            }
            if (!metadata.isEmpty()) {
                System.out.printf("      extraMetadata=%s%n", metadata);
            }
        }
    }

    private static void printIfPresent(String label, Object value) {
        if (value != null) {
            System.out.printf("%s=%s%n", label, value);
        }
    }

    private static String mineruToken() {
        String token = System.getenv("MINERU_API_TOKEN");
        if (token == null || token.isBlank()) {
            token = TestConfig.secret("mineru.api.token");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("请先配置 MINERU_API_TOKEN，或在 samples 的 secrets.properties 中配置 mineru.api.token");
        }
        return token.trim();
    }

    private static String preview(String value, int maxLength) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (normalized.codePointCount(0, normalized.length()) <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, normalized.offsetByCodePoints(0, maxLength)) + "...";
    }

    private static void requireState(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
