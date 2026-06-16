package com.agentx.ai.samples;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.chatmodels.DeepSeekV4ChatModel;
import com.agentx.ai.core.model.AgentStreamEvent;
import com.agentx.ai.core.model.PauseState;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.model.SubAgentSource;
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
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatOptions;
import org.springframework.ai.zhipuai.api.ZhiPuAiApi;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.retry.policy.NeverRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * 测试公共配置 — 所有测试类共享的工厂方法和常量。
 *
 * @author bigchui
 *
 */
public final class TestConfig {

    private static final Properties secrets = loadSecrets();

    // 便捷访问方法
    private static String s(String key) { return secrets.getProperty(key); }
    private static String s(String key, String defaultValue) { return secrets.getProperty(key, defaultValue); }
    private static int si(String key, int defaultValue) { return Integer.parseInt(s(key, String.valueOf(defaultValue))); }

    // ===== 公开常量（用于测试打印）=====
    static final String CHAT_MODEL = s("dashscope.chat.model", "qwen-plus");
    static final String EMBEDDING_MODEL = s("embedding.model", "text-embedding-v3");
    static final String SKILLS_DIR = s("skills.dir", "");

    /** HTTP 响应超时（5 分钟），思考模型非流式调用需要较长等待 */
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

    // ---------- Factory Methods ----------

    public static ChatModel createChatModel() {
        OpenAiChatOptions opts = new OpenAiChatOptions();
        opts.setModel(s("dashscope.chat.model", "qwen-plus"));
        opts.setTemperature(0.7);
        Map<String,Object> map = new HashMap<>();
        Map<String,Object> submap = new HashMap<>();
        submap.put("enable_thinking",true);
        map.put("chat_template_kwargs",submap);
        opts.setExtraBody(map);

        HttpClient httpClient = HttpClient.create().responseTimeout(HTTP_TIMEOUT);

        return OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder()
                        .baseUrl(s("dashscope.base.url", "https://dashscope.aliyuncs.com/compatible-mode/"))
                        .apiKey(s("dashscope.api.key"))
                        .restClientBuilder(RestClient.builder()
                                .requestFactory(new ReactorClientHttpRequestFactory(httpClient)))
                        .webClientBuilder(WebClient.builder()
                                .clientConnector(new ReactorClientHttpConnector(httpClient)))
                        .build())
                .defaultOptions(opts)
                .build();
    }

    public static ChatModel createMiniMaxChatModel() {
        OpenAiChatOptions opts = new OpenAiChatOptions();
        opts.setModel(s("minimax.chat.model", "MiniMax-M2.7"));
        opts.setTemperature(0.7);

        HttpClient httpClient = HttpClient.create().responseTimeout(HTTP_TIMEOUT);

        RetryTemplate noRetryTemplate = new RetryTemplate();
        noRetryTemplate.setRetryPolicy(new NeverRetryPolicy());

        return OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder()
                        .baseUrl(s("minimax.base.url", "https://api.minimaxi.com/"))
                        .apiKey(s("minimax.api.key"))
                        .restClientBuilder(RestClient.builder()
                                .requestFactory(new ReactorClientHttpRequestFactory(httpClient)))
                        .webClientBuilder(WebClient.builder()
                                .clientConnector(new ReactorClientHttpConnector(httpClient)))
                        .build())
                .defaultOptions(opts)
                .retryTemplate(noRetryTemplate)
                .build();
    }

    /**
     * 错误用法，deepseek v4不兼容openai chatmodel
     * @return
     */
    public static ChatModel createDeepSeekChatModel() {
        OpenAiChatOptions opts = new OpenAiChatOptions();
        opts.setModel(s("deepseek.chat.model", "deepseek-chat"));
        opts.setTemperature(0.7);

        HttpClient httpClient = HttpClient.create().responseTimeout(HTTP_TIMEOUT);

        RetryTemplate noRetryTemplate = new RetryTemplate();
        noRetryTemplate.setRetryPolicy(new NeverRetryPolicy());

        return OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder()
                        .baseUrl(s("deepseek.base.url", "https://api.deepseek.com"))
                        .apiKey(s("deepseek.api.key"))
                        .restClientBuilder(RestClient.builder()
                                .requestFactory(new ReactorClientHttpRequestFactory(httpClient)))
                        .webClientBuilder(WebClient.builder()
                                .clientConnector(new ReactorClientHttpConnector(httpClient)))
                        .build())
                .defaultOptions(opts)
                .retryTemplate(noRetryTemplate)
                .build();
    }

    /**
     * 正确用法
     * @return
     */
    public static ChatModel createDeepSeekV4ChatModel(){
        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .apiKey(s("deepseek.api.key"))
                .baseUrl(s("deepseek.base.url"))
                .build();
        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model(s("deepseek.chat.model"))
                .temperature(0.7)
                .build();
        DeepSeekV4ChatModel chatModel = DeepSeekV4ChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(options)
                .build();
        return chatModel;
    }

    public static ChatModel createZhiPuChatModel() {
        ZhiPuAiApi api = ZhiPuAiApi.builder()
                .baseUrl("https://open.bigmodel.cn/api/coding/paas/")
                .apiKey(s("zhipu.api.key"))
                .build();

        ZhiPuAiChatOptions options = ZhiPuAiChatOptions.builder()
                .model(s("zhipu.chat.model", "glm-4.7"))
                .temperature(0.7)
                .thinking(ZhiPuAiApi.ChatCompletionRequest.Thinking.enabled())
                .build();

        // 禁用 Spring AI 内部重试，由 AgentX 框架统一处理重试
        RetryTemplate noRetryTemplate = new RetryTemplate();
        noRetryTemplate.setRetryPolicy(new NeverRetryPolicy());

        return new ZhiPuAiChatModel(api, options, noRetryTemplate);
    }

    /**
     * 创建 OpenAI 兼容风格的 ZhiPu ChatModel。
     * <p>
     * 通过 OpenAI 兼容接口访问 GLM 模型，支持 {@code reasoning_content} 字段提取。
     * <p>
     * 与 {@link #createZhiPuChatModel()} 的区别：
     * <ul>
     *   <li>原生 ZhiPu 实现 — 不提取 reasoning_content，无法使用 REASONING_CONTENT 模式</li>
     *   <li>OpenAI 兼容接口 — 正确提取 reasoning_content 到 metadata，支持 REASONING_CONTENT 模式</li>
     * </ul>
     */
    public static ChatModel createZhiPuOpenAiChatModel() {
        OpenAiChatOptions opts = new OpenAiChatOptions();
        opts.setModel(s("zhipu.chat.model", "glm-4.7"));
        opts.setTemperature(0.7);

        // 思考模型非流式调用需要更长超时（模型生成完整响应后才开始发送）
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(HTTP_TIMEOUT);

        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(new ReactorClientHttpRequestFactory(httpClient));
        WebClient.Builder webClientBuilder = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));

        RetryTemplate noRetryTemplate = new RetryTemplate();
        noRetryTemplate.setRetryPolicy(new NeverRetryPolicy());

        return OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder()
                        .baseUrl(s("zhipu.openai.base.url", "https://open.bigmodel.cn/api/coding/paas/v4"))
                        .apiKey(s("zhipu.api.key"))
                        .completionsPath("/chat/completions")
                        .restClientBuilder(restClientBuilder)
                        .webClientBuilder(webClientBuilder)
                        .build())
                .defaultOptions(opts)
                .retryTemplate(noRetryTemplate)
                .build();
    }

    public static EmbeddingModel createEmbeddingModel() {
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(EMBEDDING_MODEL)
                .dimensions(1024)
                .build();

        return new OpenAiEmbeddingModel(
                OpenAiApi.builder()
                        .baseUrl(s("dashscope.base.url", "https://dashscope.aliyuncs.com/compatible-mode/"))
                        .apiKey(s("dashscope.api.key"))
                        .build(),
                MetadataMode.EMBED,
                options
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
        JdbcTemplate jdbcTemplate = new JdbcTemplate(pgDataSource);
        PgVectorStore store = PgVectorStore.builder(jdbcTemplate, embeddingModel)
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

    /**
     * 流式调用 Agent 并实时打印输出。
     */
    public static void streamAndPrint(ReactAgent agent, String query) {
        streamAndPrint(agent, query, null);
    }

    public static void streamAndPrint(ReactAgent agent, String query, RunnableParams params) {
        System.out.println("Q: " + query);
        System.out.print("A: ");
        if (params != null) {
            agent.stream(query, params)
                    .doOnNext(chunk -> {
                        System.out.print(chunk);
                        System.out.flush();
                    })
                    .doOnError(err -> System.err.println("\nError: " + err.getMessage()))
                    .blockLast();
        } else {
            agent.stream(query)
                    .doOnNext(chunk -> {
                        System.out.print(chunk);
                        System.out.flush();
                    })
                    .doOnError(err -> System.err.println("\nError: " + err.getMessage()))
                    .blockLast();
        }
        System.out.println("\n");
    }

    public static void printTestHeader(String title) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("  " + title);
        System.out.println("=".repeat(60) + "\n");
    }

    // ---------- 流式事件工具方法 ----------

    /**
     * 上一个事件是否为 Thinking，用于控制标记打印
     */
    private static boolean lastEventWasThinking = false;

    /**
     * 当前事件来源的 Agent 名称（null = 主 Agent），用于 SubAgent 分隔
     */
    private static String currentAgentName = null;

    /**
     * 从事件中提取 SubAgentSource。
     */
    private static SubAgentSource extractSource(AgentStreamEvent event) {
        return switch (event) {
            case AgentStreamEvent.Text e -> e.source();
            case AgentStreamEvent.Thinking e -> e.source();
            case AgentStreamEvent.ToolStart e -> e.source();
            case AgentStreamEvent.ToolEnd e -> e.source();
            case AgentStreamEvent.Complete e -> e.source();
            default -> null;
        };
    }

    /**
     * 格式化打印单个流式事件。
     * <p>
     * - SubAgent 来源切换时打印 ━━━ [agent名] ━━━ 分隔线
     * - Thinking 事件只在进入思考时打印一次标记，避免每个 chunk 重复前缀
     */
    public static void printEvent(AgentStreamEvent event) {
        // SubAgent 来源切换检测
        SubAgentSource source = extractSource(event);
        String agentName = source != null ? source.agentName() : null;
        if (!java.util.Objects.equals(agentName, currentAgentName)) {
            if (currentAgentName != null) System.out.println();
            currentAgentName = agentName;
            lastEventWasThinking = false;
            String label = agentName != null ? agentName : "main";
            System.out.println("\n━━━ [" + label + "] ━━━");
        }

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
            case AgentStreamEvent.TodoProgress tp -> {
                System.out.println("[TodoProgress] 任务列表更新:");
                for (var item : tp.items()) {
                    String icon = switch (item.status()) {
                        case completed -> "[✓]";
                        case in_progress -> "[→]";
                        case pending -> "[ ]";
                    };
                    System.out.println("  " + icon + " " + item.content());
                }
            }
            case AgentStreamEvent.StageOutput so ->
                    System.out.println("\n[StageOutput:" + so.stage() + "] " + so.data());
            case AgentStreamEvent.Error e ->
                    System.out.println("\n[Error:" + e.code().code() + "] " + e.message() + "\n  " + e.detail());
            case AgentStreamEvent.Complete c -> System.out.println("\n[Complete]：promptTokens："+c.totalPromptTokens()+ "，completionTokens："+c.totalCompletionTokens());
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
