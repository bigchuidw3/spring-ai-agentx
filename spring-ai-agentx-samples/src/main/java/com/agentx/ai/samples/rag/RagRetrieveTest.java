package com.agentx.ai.samples.rag;

import com.agentx.ai.rag.asset.LocalDocumentAssetStore;
import com.agentx.ai.rag.common.DocumentIdGenerator;
import com.agentx.ai.rag.parser.mineru.MineruDocumentParser;
import com.agentx.ai.rag.pipeline.DefaultRagPipeline;
import com.agentx.ai.rag.pipeline.RagPipeline;
import com.agentx.ai.rag.reader.LocalDocumentReader;
import com.agentx.ai.rag.reader.RawDocument;
import com.agentx.ai.rag.retrieve.ParentChildDocumentRetriever;
import com.agentx.ai.rag.splitter.HeadingSplitter;
import com.agentx.ai.rag.store.RagDocumentStore;
import com.agentx.ai.rag.store.RedisDocumentStore;
import com.agentx.ai.rag.store.RedisVectorStores;
import com.agentx.ai.samples.TestConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * RAG 完整链路测试：MinerU 多模态解析 → 父子分块 → Redis 持久化 → 内置 Pipeline 检索。
 *
 * 向量库与父块存储均为 Redis。需要配置 MINERU_API_TOKEN 环境变量
 * 或 secrets.properties 的 mineru.api.token。
 * 索引以内容 hash 作 documentId，先删后插幂等，可重复运行。
 *
 * @author bigchui
 */
public class RagRetrieveTest {

    private static final Path DEFAULT_SAMPLE_FILE = Path.of(
            "D:\\download\\✅Claude Code中如何使用Skills.docx");

    private static final String REDIS_HOST = "192.168.113.52";
    private static final int REDIS_PORT = 6399;
    private static final String REDIS_PASSWORD = "8uhb*UHB";

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));

        Path sampleFile = args.length > 0 ? Path.of(args[0]) : DEFAULT_SAMPLE_FILE;
        TestConfig.printTestHeader("RAG Full Pipeline Test");

        // 1. MinerU 多模态解析：图片保存为资产，URI 写回 Markdown，VLM 生成图片语义描述
        RawDocument rawDocument = new LocalDocumentReader(sampleFile).read();
        List<Document> sourceDocuments = MineruDocumentParser.builder()
                .authToken(mineruToken())
                .enableImageUnderstanding(true)
                .assetStore(new LocalDocumentAssetStore(Path.of(".rag-tmp6", "rag-assets")))
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

        // 3. 持久化：子块向量化 + 父块原文，全进 Redis
        RedisVectorStore vectorStore = createRedisVectorStore();
        RedisDocumentStore parentStore = new RedisDocumentStore(createStringRedisTemplate());
        RagDocumentStore documentStore = RagDocumentStore.builder()
                .vectorStore(vectorStore)
                .documentStore(parentStore)
                .build();
        documentStore.index(DocumentIdGenerator.getDocId(rawDocument.content()), chunks);
        System.out.printf("持久化完成%n%n");

        // 4. 组装内置 Pipeline：查询压缩 → 多查询扩展 → 向量检索 → 父块回查
        ChatClient chatClient = ChatClient.builder(TestConfig.createChatModel()).build();
        RagPipeline pipeline = DefaultRagPipeline.builder()
                .queryTransformer(CompressionQueryTransformer.builder()
                        .chatClientBuilder(chatClient.mutate())
                        .build())
                .queryExpander(MultiQueryExpander.builder()
                        .chatClientBuilder(chatClient.mutate())
                        .numberOfQueries(2)
                        .includeOriginal(true)
                        .build())
                .documentRetriever(new ParentChildDocumentRetriever(
                        VectorStoreDocumentRetriever.builder()
                                .vectorStore(vectorStore)
                                .topK(5)
                                .build(),
                        parentStore))
                .build();

        // 5. 多问题检索验证
        String[] questions = {
                "如何创建一个 Skill",
                "Skill 的目录结构是什么",
                "Skill 和 MCP 有什么区别"
        };
        for (String question : questions) {
            List<Document> docs = pipeline.retrieve(question, List.of());
            printDocuments(question, docs);
            requireState(!docs.isEmpty(), "检索必须返回结果: " + question);
        }
        System.out.println("\n校验通过：MinerU 解析 → 父子分块 → 持久化 → Pipeline 检索全链路正常");
    }

    private static RedisVectorStore createRedisVectorStore() {
        DefaultJedisClientConfig jedisConfig = DefaultJedisClientConfig.builder()
                .password(REDIS_PASSWORD)
                .connectionTimeoutMillis(10000)
                .socketTimeoutMillis(10000)
                .build();
        JedisPooled jedis = new JedisPooled(new HostAndPort(REDIS_HOST, REDIS_PORT), jedisConfig);
        return RedisVectorStores.create(jedis, TestConfig.createEmbeddingModel());
    }

    private static StringRedisTemplate createStringRedisTemplate() {
        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration(REDIS_HOST, REDIS_PORT);
        redisConfig.setPassword(RedisPassword.of(REDIS_PASSWORD));
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(redisConfig);
        connectionFactory.afterPropertiesSet();
        return new StringRedisTemplate(connectionFactory);
    }

    private static void printDocuments(String question, List<Document> documents) {
        System.out.printf("%n检索 \"%s\" -> %d 个父块%n", question, documents.size());
        for (int i = 0; i < documents.size(); i++) {
            System.out.printf("  [%d] id=%s%n      text=%s%n",
                    i, documents.get(i).getId(), preview(documents.get(i).getText(), 150));
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
