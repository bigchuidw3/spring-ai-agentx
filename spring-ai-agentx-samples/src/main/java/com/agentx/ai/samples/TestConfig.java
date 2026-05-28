package com.agentx.ai.samples;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.chatmodels.DeepSeekV4ChatModel;
import com.agentx.ai.core.model.AgentStreamEvent;
import com.agentx.ai.core.model.PauseState;
import com.agentx.ai.core.model.RunnableParams;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.zhipuai.ZhiPuAiChatOptions;
import org.springframework.ai.zhipuai.api.ZhiPuAiApi;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.policy.NeverRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;

/**
 * 测试公共配置 — 所有测试类共享的工厂方法和常量。
 *
 * @author bigchui
 */
public final class TestConfig {

    private static final Properties secrets = loadSecrets();

    private static String s(String key) { return secrets.getProperty(key); }
    private static String s(String key, String defaultValue) { return secrets.getProperty(key, defaultValue); }
    private static int si(String key, int defaultValue) { return Integer.parseInt(s(key, String.valueOf(defaultValue))); }

    static final String CHAT_MODEL = s("dashscope.chat.model", "qwen-plus");
    static final String EMBEDDING_MODEL = s("embedding.model", "text-embedding-v3");
    static final String SKILLS_DIR = s("skills.dir", "");

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(300);

    private TestConfig() {
    }

    private static Properties loadSecrets() {
        Properties props = new Properties();
        try (InputStream is = TestConfig.class.getClassLoader().getResourceAsStream("secrets.properties")) {
            if (is != null) {
                props.load(is);
            } else {
                System.err.println("[TestConfig] 未找到 secrets.properties，请复制 secrets.properties.example 并填入真实值");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load secrets.properties", e);
        }
        return props;
    }

    // ---------- Common Helpers ----------

    private static HttpClient httpClient() {
        return HttpClient.create().responseTimeout(HTTP_TIMEOUT);
    }

    private static RetryTemplate noRetry() {
        RetryTemplate rt = new RetryTemplate();
        rt.setRetryPolicy(new NeverRetryPolicy());
        return rt;
    }

    private static OpenAiApi.Builder openAiApiBuilder(String baseUrl, String apiKeyKey) {
        HttpClient hc = httpClient();
        return OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(s(apiKeyKey))
                .restClientBuilder(RestClient.builder()
                        .requestFactory(new ReactorClientHttpRequestFactory(hc)))
                .webClientBuilder(WebClient.builder()
                        .clientConnector(new ReactorClientHttpConnector(hc)));
    }

    // ---------- ChatModel Factory Methods ----------

    /**
     * Qwen (DashScope) — 通过 OpenAI 兼容接口接入。
     */
    public static ChatModel createChatModel() {
        OpenAiChatOptions opts = new OpenAiChatOptions();
        opts.setModel(s("dashscope.chat.model", "qwen-plus"));
        opts.setTemperature(0.7);

        return OpenAiChatModel.builder()
                .openAiApi(openAiApiBuilder(
                        s("dashscope.base.url", "https://dashscope.aliyuncs.com/compatible-mode/"),
                        "dashscope.api.key").build())
                .defaultOptions(opts)
                .build();
    }

    /**
     * DeepSeek V4 — 使用框架内置 DeepSeekV4ChatModel（修复 reasoning_content 回传兼容性）。
     */
    public static ChatModel createDeepSeekV4ChatModel() {
        return DeepSeekV4ChatModel.builder()
                .deepSeekApi(DeepSeekApi.builder()
                        .apiKey(s("deepseek.api.key"))
                        .baseUrl(s("deepseek.base.url"))
                        .build())
                .defaultOptions(DeepSeekChatOptions.builder()
                        .model(s("deepseek.chat.model"))
                        .temperature(0.7)
                        .build())
                .build();
    }

    /**
     * GLM (ZhiPu) — 通过 OpenAI 兼容接口接入，支持 reasoning_content 字段提取。
     */
    public static ChatModel createZhiPuChatModel() {
        OpenAiChatOptions opts = new OpenAiChatOptions();
        opts.setModel(s("zhipu.chat.model", "glm-4.7"));
        opts.setTemperature(0.7);

        return OpenAiChatModel.builder()
                .openAiApi(openAiApiBuilder(
                        s("zhipu.openai.base.url"),
                        "zhipu.api.key")
                        .completionsPath("/chat/completions")
                        .build())
                .defaultOptions(opts)
                .retryTemplate(noRetry())
                .build();
    }

    /**
     * MiniMax — 通过 OpenAI 兼容接口接入，支持 {@code <think/>} 标签格式。
     */
    public static ChatModel createMiniMaxChatModel() {
        OpenAiChatOptions opts = new OpenAiChatOptions();
        opts.setModel(s("minimax.chat.model", "MiniMax-M2.7"));
        opts.setTemperature(0.7);

        return OpenAiChatModel.builder()
                .openAiApi(openAiApiBuilder(
                        s("minimax.base.url", "https://api.minimaxi.com/"),
                        "minimax.api.key").build())
                .defaultOptions(opts)
                .retryTemplate(noRetry())
                .build();
    }

    // ---------- Infrastructure Factory Methods ----------

    public static EmbeddingModel createEmbeddingModel() {
        return new OpenAiEmbeddingModel(
                OpenAiApi.builder()
                        .baseUrl(s("dashscope.base.url", "https://dashscope.aliyuncs.com/compatible-mode/"))
                        .apiKey(s("dashscope.api.key"))
                        .build(),
                MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .model(EMBEDDING_MODEL)
                        .dimensions(1024)
                        .build()
        );
    }

    public static DataSource createMySqlDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(s("mysql.url", "jdbc:mysql://localhost:3306/agent_test?useSSL=false&allowPublicKeyRetrieval=true"));
        ds.setUsername(s("mysql.user", "root"));
        ds.setPassword(s("mysql.password", "root"));
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return ds;
    }

    public static DataSource createPgDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(String.format("jdbc:postgresql://%s:%d/%s",
                s("pg.host", "192.168.11.163"),
                si("pg.port", 5433),
                s("pg.database", "vector_store")));
        ds.setUsername(s("pg.user", "postgres"));
        ds.setPassword(s("pg.password", "postgres"));
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    public static PgVectorStore createPgVectorStore(DataSource pgDataSource, EmbeddingModel embeddingModel) {
        PgVectorStore store = PgVectorStore.builder(new JdbcTemplate(pgDataSource), embeddingModel)
                .dimensions(1024)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)
                .removeExistingVectorStoreTable(false)
                .vectorTableName(s("pg.table.name", "agent_semantic_memory"))
                .maxDocumentBatchSize(100)
                .build();
        try {
            store.afterPropertiesSet();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize PgVectorStore", e);
        }
        return store;
    }

    // ---------- Helper Methods ----------

    public static String randomConvId() {
        return "conv_" + UUID.randomUUID();
    }

    public static String randomUserId(String prefix) {
        return prefix + "_" + System.currentTimeMillis();
    }

    public static RunnableParams buildParams(String convId, String userId) {
        return RunnableParams.builder()
                .conversationId(convId)
                .userId(userId)
                .build();
    }

    public static void printTestHeader(String title) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("  " + title);
        System.out.println("=".repeat(60) + "\n");
    }

    // ---------- Stream Helpers ----------

    public static void streamAndPrint(ReactAgent agent, String query) {
        streamAndPrint(agent, query, null);
    }

    public static void streamAndPrint(ReactAgent agent, String query, RunnableParams params) {
        System.out.println("Q: " + query);
        System.out.print("A: ");
        (params != null ? agent.stream(query, params) : agent.stream(query))
                .doOnNext(chunk -> {
                    System.out.print(chunk);
                    System.out.flush();
                })
                .doOnError(err -> System.err.println("\nError: " + err.getMessage()))
                .blockLast();
        System.out.println("\n");
    }

    private static boolean lastEventWasThinking = false;

    /**
     * 格式化打印单个流式事件。
     * Thinking 事件只在进入思考时打印一次标记，避免每个 chunk 重复前缀。
     */
    public static void printEvent(AgentStreamEvent event) {
        switch (event) {
            case AgentStreamEvent.AgentStart s -> System.out.println("[AgentStart]");
            case AgentStreamEvent.Thinking t -> {
                if (!lastEventWasThinking) {
                    System.out.print("\n[Thinking] ");
                }
                System.out.print(t.content());
                lastEventWasThinking = true;
            }
            case AgentStreamEvent.Text t -> {
                if (lastEventWasThinking) {
                    System.out.println("\n---");
                    System.out.println("\n最终答案：");
                }
                System.out.print(t.content());
                lastEventWasThinking = false;
            }
            case AgentStreamEvent.ToolStart ts ->
                    System.out.println("\n[ToolStart] " + ts.toolName() + " id=" + ts.toolCallId());
            case AgentStreamEvent.ToolEnd te -> System.out.println("[ToolEnd] " + te.toolName() + " result="
                    + (te.result() != null && te.result().length() > 80
                    ? te.result().substring(0, 80) + "..."
                    : te.result()));
            case AgentStreamEvent.StageOutput so ->
                    System.out.println("\n[StageOutput:" + so.stage() + "] " + so.data());
            case AgentStreamEvent.Error e ->
                    System.out.println("\n[Error:" + e.code().code() + "] " + e.message() + "\n  " + e.detail());
            case AgentStreamEvent.Complete c -> System.out.println("\n[Complete]");
            case AgentStreamEvent.Paused p -> System.out.println("[Paused]");
        }
    }

    /**
     * 收集流式事件，实时打印所有事件，返回 PauseState（如果暂停）。
     */
    public static PauseState collectStreamEvents(Flux<AgentStreamEvent> flux) {
        PauseState[] holder = new PauseState[1];
        flux.doOnNext(event -> {
            if (event instanceof AgentStreamEvent.Paused p) {
                holder[0] = p.state();
            } else {
                printEvent(event);
            }
        }).blockLast();
        return holder[0];
    }
}
