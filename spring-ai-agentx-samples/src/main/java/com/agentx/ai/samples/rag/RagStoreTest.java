package com.agentx.ai.samples.rag;

import com.agentx.ai.rag.common.MetadataKeys;
import com.agentx.ai.rag.common.ChunkRole;
import com.agentx.ai.rag.parser.DocumentParser;
import com.agentx.ai.rag.parser.tika.TikaDocumentParser;
import com.agentx.ai.rag.reader.LocalDocumentReader;
import com.agentx.ai.rag.reader.RawDocument;
import com.agentx.ai.rag.splitter.HeadingSplitter;
import com.agentx.ai.rag.splitter.RecursiveSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import com.agentx.ai.samples.TestConfig;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import javax.sql.DataSource;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * RAG 存储层测试。
 *
 * <p>修改 testNumber 切换测试场景：
 * <pre>
 * 1: PgVectorStore — 向量语义检索（需要 PG 环境）
 * 2: ParentChild 过滤检索 — 父块/子块 metadata filter 验证
 * </pre>
 *
 * @author bigchui
 */
public class RagStoreTest {

    static int testNumber = 1;

    private static final Path DEFAULT_SAMPLE_FILE = Path.of(
            "C:\\Users\\Lenovo\\Desktop\\notes\\new\\tc\\25-执行验证工具-台词.md");

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));

        Path sampleFile = args.length > 0 ? Path.of(args[0]) : DEFAULT_SAMPLE_FILE;
        TestConfig.printTestHeader("RAG Store Test #" + testNumber);

        switch (testNumber) {
            case 1 -> testPgVectorStore(sampleFile);
            case 2 -> testParentChildFilter(sampleFile);
            default -> System.out.println("Unknown test number: " + testNumber);
        }
    }

    static void testPgVectorStore(Path sampleFile) throws Exception {
        List<Document> chunks = parseAndChunk(sampleFile);

        DataSource pgDataSource = TestConfig.createPgDataSource();
        var embeddingModel = TestConfig.createEmbeddingModel();

        VectorStore store = TestConfig.createPgVectorStore(pgDataSource, embeddingModel);
        store.add(chunks);
        System.out.printf("已向量化写入 %d 个块到 PgVectorStore%n", chunks.size());

        String[] queries = {"如何使用验证工具", "执行验证的流程是什么", "验证工具的台词怎么写"};
        for (String query : queries) {
            List<Document> results = store.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(3)
                    .similarityThreshold(0.5)
                    .build());
            System.out.printf("%n语义检索: \"%s\" -> %d 个结果%n", query, results.size());
            for (int i = 0; i < results.size(); i++) {
                Document doc = results.get(i);
                System.out.printf("  [%d] id=%s, score=%s, text=%s%n",
                        i, doc.getId(), doc.getScore(), preview(doc.getText(), 100));
            }
        }

        requireState(!store.similaritySearch(SearchRequest.builder()
                .query("验证工具").topK(1).build()).isEmpty(),
                "语义检索必须返回结果");
        System.out.println("\n校验通过：PgVectorStore 语义检索正常");
    }

    static void testParentChildFilter(Path sampleFile) throws Exception {
        List<Document> chunks = parseAndChunkWithParentChild(sampleFile);

        int parentCount = 0;
        int childCount = 0;
        for (Document chunk : chunks) {
            String role = (String) chunk.getMetadata().get(MetadataKeys.CHUNK_ROLE);
            if (ChunkRole.PARENT.code().equals(role)) parentCount++;
            else if (ChunkRole.CHILD.code().equals(role)) childCount++;
        }
        System.out.printf("总块=%d, 父块=%d, 子块=%d%n", chunks.size(), parentCount, childCount);

        DataSource pgDataSource = TestConfig.createPgDataSource();
        var embeddingModel = TestConfig.createEmbeddingModel();
        VectorStore store = TestConfig.createPgVectorStore(pgDataSource, embeddingModel);
        store.add(chunks);
        System.out.printf("已写入 %d 个块（含父块）%n", chunks.size());

        List<Document> allResults = store.similaritySearch(SearchRequest.builder()
                .query("验证工具").topK(10).similarityThreshold(0.3).build());
        System.out.printf("%n不带 filter 检索 -> %d 个结果%n", allResults.size());

        FilterExpressionBuilder fb = new FilterExpressionBuilder();
        List<Document> filteredResults = store.similaritySearch(SearchRequest.builder()
                .query("验证工具")
                .topK(10)
                .similarityThreshold(0.3)
                .filterExpression(fb.ne(MetadataKeys.SKIP_EMBEDDING, 1).build())
                .build());
        System.out.printf("排除父块 filter 检索 -> %d 个结果%n", filteredResults.size());

        for (int i = 0; i < filteredResults.size(); i++) {
            Document doc = filteredResults.get(i);
            Object skipEmbedding = doc.getMetadata().get(MetadataKeys.SKIP_EMBEDDING);
            requireState(skipEmbedding == null || !Integer.valueOf(1).equals(skipEmbedding),
                    "过滤后的结果不应包含 skipEmbedding=1 的父块");
            System.out.printf("  [%d] role=%s, text=%s%n",
                    i, doc.getMetadata().get(MetadataKeys.CHUNK_ROLE), preview(doc.getText(), 80));
        }

        requireState(parentCount > 0, "必须有父块");
        requireState(childCount > 0, "必须有子块");
        System.out.println("\n校验通过：父块/子块 metadata filter 正常");
    }

    private static List<Document> parseAndChunk(Path sampleFile) throws Exception {
        DocumentParser parser = new TikaDocumentParser();
        RawDocument rawDocument = new LocalDocumentReader(sampleFile).read();
        List<org.springframework.ai.document.Document> sourceDocuments = parser.parseToDocuments(rawDocument);
        return RecursiveSplitter.builder()
                .chunkSize(300).overlap(50).build()
                .split(sourceDocuments);
    }

    private static List<Document> parseAndChunkWithParentChild(Path sampleFile) throws Exception {
        DocumentParser parser = new TikaDocumentParser();
        RawDocument rawDocument = new LocalDocumentReader(sampleFile).read();
        List<org.springframework.ai.document.Document> sourceDocuments = parser.parseToDocuments(rawDocument);
        return HeadingSplitter.builder()
                .maxHeadingLevel(2).chunkSize(300).overlap(50)
                .enableParentChild(true).build()
                .split(sourceDocuments);
    }

    private static String preview(String value, int maxLength) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (normalized.codePointCount(0, normalized.length()) <= maxLength) return normalized;
        return normalized.substring(0, normalized.offsetByCodePoints(0, maxLength)) + "...";
    }

    private static void requireState(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}