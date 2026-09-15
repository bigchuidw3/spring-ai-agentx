package com.agentx.ai.samples.rag;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.model.ThinkingMode;
import com.agentx.ai.rag.asset.MinioDocumentAssetStore;
import com.agentx.ai.rag.common.DocumentIdGenerator;
import com.agentx.ai.rag.common.MetadataKeys;
import com.agentx.ai.rag.parser.mineru.MineruDocumentParser;
import com.agentx.ai.rag.pipeline.DefaultRagPipeline;
import com.agentx.ai.rag.pipeline.RagPipeline;
import com.agentx.ai.rag.pipeline.RagRetrievalTool;
import com.agentx.ai.rag.query.QueryExpanders;
import com.agentx.ai.rag.query.QueryTransformers;
import com.agentx.ai.rag.reader.LocalDocumentReader;
import com.agentx.ai.rag.reader.RawDocument;
import com.agentx.ai.rag.retrieve.EnhancedRetriever;
import com.agentx.ai.rag.splitter.HeadingSplitter;
import com.agentx.ai.rag.store.RagDocumentStore;
import com.agentx.ai.rag.store.RedisDocumentStore;
import com.agentx.ai.samples.TestConfig;
import io.minio.MinioClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * RAG 完整链路测试（PG 向量库）：MinerU 多模态解析 → 父子分块 → 持久化 → 内置 Pipeline 检索。
 *
 * 向量库为 PgVectorStore，父块存 Redis。需要配置 MINERU_API_TOKEN 环境变量
 * 或 secrets.properties 的 mineru.api.token。
 * 索引以内容 hash 作 documentId，先删后插幂等，可重复运行。
 *
 * @author bigchui
 */
public class RagPgRetrieveTest {

    private static final Path DEFAULT_SAMPLE_FILE = Path.of(
            "D:\\download\\✅Claude Code中如何使用Skills.docx");

    private static final String PG_VECTOR_TABLE = "agentx_rag_vector";

    private static final String REDIS_HOST = "192.168.113.52";
    private static final int REDIS_PORT = 6399;
    private static final String REDIS_PASSWORD = "8uhb*UHB";

    private static final String MINIO_ENDPOINT = "http://192.168.113.52:19000";
    private static final String MINIO_ACCESS_KEY = "minioadmin";
    private static final String MINIO_SECRET_KEY = "minioadmin";
    private static final String MINIO_BUCKET = "rag-assets";

    /**
     * 自定义文档类型 metadata，入库时注入，检索时用于过滤。
     */
    private static final String DOC_TYPE = "docType";
    private static final String DOC_TYPE_VALUE = "aicube";

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));

        Path sampleFile = args.length > 0 ? Path.of(args[0]) : DEFAULT_SAMPLE_FILE;

        // 1 = 建索引，2 = 检索问答
        int testNumber = 2;
        if (testNumber == 1) {
            indexDocuments(sampleFile);
        } else {
            retrieve(sampleFile);
        }
    }

    /**
     * 建索引：MinerU 解析 → 父子分块 → 持久化（子块进 PG，父块进 Redis，注入 docType）。
     */
    static void indexDocuments(Path sampleFile) throws Exception {
        TestConfig.printTestHeader("RAG PG Index");

        // 1. MinerU 多模态解析：图片上传 MinIO，URI 写回 Markdown，VLM 生成图片语义描述
        RawDocument rawDocument = new LocalDocumentReader(sampleFile).read();
        List<Document> sourceDocuments = MineruDocumentParser.builder()
                .authToken(mineruToken())
                .enableImageUnderstanding(true)
                .assetStore(new MinioDocumentAssetStore(createMinioClient(), MINIO_BUCKET, MINIO_ENDPOINT))
                .imageChatModel(TestConfig.createMultimodalChatModel())
                .imageWorkers(4)
                .build()
                .parseToDocuments(rawDocument);
        System.out.printf("解析完成：可分块文本块 %d 个%n", sourceDocuments.size());

        // 2. 父子分块：完整小节作父块，小节内部切子块
        List<Document> chunks = HeadingSplitter.builder()
                .maxHeadingLevel(2).chunkSize(600).overlap(80)
                .enableParentChild(true).build()
                .split(sourceDocuments);
        System.out.printf("分块完成：共 %d 个块%n", chunks.size());

        // 3. 持久化：子块向量化进 PG，父块原文存 Redis，统一注入 docType 自定义元数据
        DataSource pgDataSource = TestConfig.createPgDataSource();
        VectorStore vectorStore = TestConfig.createPgVectorStore(
                pgDataSource, TestConfig.createEmbeddingModel(), PG_VECTOR_TABLE);
        RedisDocumentStore parentStore = new RedisDocumentStore(createStringRedisTemplate());
        RagDocumentStore documentStore = RagDocumentStore.builder()
                .vectorStore(vectorStore)
                .documentStore(parentStore)
                .build();
        documentStore.index(DocumentIdGenerator.getDocId(rawDocument.content()), chunks,
                Map.of(DOC_TYPE, DOC_TYPE_VALUE));
        System.out.printf("持久化完成%n%n");
    }

    /**
     * 检索问答：组装 Pipeline → 封装成工具注入 ReactAgent → 真实 Agentic RAG 问答。
     */
    static void retrieve(Path sampleFile) throws Exception {
        TestConfig.printTestHeader("RAG PG Retrieve");

        DataSource pgDataSource = TestConfig.createPgDataSource();
        VectorStore vectorStore = TestConfig.createPgVectorStore(
                pgDataSource, TestConfig.createEmbeddingModel(), PG_VECTOR_TABLE);
        RedisDocumentStore parentStore = new RedisDocumentStore(createStringRedisTemplate());

        // 组装内置 Pipeline：全量查询增强（压缩 → 改写 → HyDE + 多查询扩展）→ 父子检索
        ChatModel queryChatModel = TestConfig.createChatModel();
        FilterExpressionBuilder fb = new FilterExpressionBuilder();
        Filter.Expression filter = fb.and(
                fb.eq(DOC_TYPE, DOC_TYPE_VALUE),
                fb.eq(MetadataKeys.FILE_NAME, "✅Claude Code中如何使用Skills.docx")).build();

        RagPipeline pipeline = DefaultRagPipeline.builder()
                .queryTransformer(QueryTransformers.compression(queryChatModel))
                .queryTransformer(QueryTransformers.hyde(queryChatModel))
                .queryExpander(QueryExpanders.multiQuery(queryChatModel))
                .documentRetriever(EnhancedRetriever.builder()
                        .vectorStore(vectorStore)
                        .documentStore(parentStore)
                        .topK(5)
                        .filterExpression(filter)
                        .enableParentChild(true)
                        .build())
                .build();

        // 封装成工具注入 ReactAgent，真实走一遍 Agentic RAG 问答
        ReactAgent agent = ReactAgent.builder()
                .chatModel(TestConfig.createChatModel())
                .thinkingMode(ThinkingMode.REASONING_CONTENT)
                .tools(RagRetrievalTool.of(pipeline))
                .instructions("""
                        你是一个文档知识助手。基于检索到的文档内容回答用户问题。
                        回答要求：
                        1. 使用标准的 Markdown 格式输出
                        2. 如果文档内容包含图片引用（形如 ![](url)），用标准 Markdown 图片语法原样渲染，输出图文并茂的内容
                        3. 如果检索结果不足以回答问题，说明信息不足，不要编造，委婉告知用户没有检索到相关内容即可
                        4. 当用户问题不明确，或者与检索出的结果存在冲突、或者匹配上多个不同的说明的时候，请主动像用户提问，要求输入明确的问题。
                        """)
                .build();

        String question = "如何创建一个 Skill？";
        System.out.println("Q: " + question);
        agent.streamForResult(question, RunnableParams.empty())
                .doOnNext(TestConfig::printEvent)
                .doOnError(e -> System.err.println("Stream Error: " + e.getMessage()))
                .blockLast();
    }

    private static StringRedisTemplate createStringRedisTemplate() {
        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration(REDIS_HOST, REDIS_PORT);
        redisConfig.setPassword(RedisPassword.of(REDIS_PASSWORD));
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(redisConfig);
        connectionFactory.afterPropertiesSet();
        return new StringRedisTemplate(connectionFactory);
    }

    private static MinioClient createMinioClient() {
        return MinioClient.builder()
                .endpoint(MINIO_ENDPOINT)
                .credentials(MINIO_ACCESS_KEY, MINIO_SECRET_KEY)
                .build();
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
}
