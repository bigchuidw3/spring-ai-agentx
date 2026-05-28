package com.agentx.ai.samples;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.model.AgentResult;
import com.agentx.ai.core.model.OutputType;
import com.agentx.ai.core.model.RunnableParams;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

/**
 * 结构化输出测试。
 *
 * <p>测试内容：
 * <ul>
 *   <li>测试 1：call() + outputType(Class) — 输出单个对象 JSON</li>
 *   <li>测试 2：call() + OutputType.listOf(Class) — 输出 List 集合 JSON</li>
 *   <li>测试 3：callForResult() — 单对象 + 集合对象</li>
 * </ul>
 *
 * @author bigchui
 *
 */
public class StructuredOutputTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // ===== 测试用 POJO =====

    public static class BookInfo {
        @JsonProperty("title")
        private String title;

        @JsonProperty("author")
        private String author;

        @JsonProperty("year")
        private int year;

        @JsonProperty("summary")
        private String summary;

        public BookInfo() {
        }

        @Override
        public String toString() {
            return "BookInfo{title='%s', author='%s', year=%d, summary='%s'}"
                    .formatted(title, author, year, summary);
        }
    }

    // ===== 测试方法 =====

    /**
     * 测试 1：call() + outputType(Class) — 输出单对象 JSON。
     *
     * <p>框架自动注入 JSON schema 到 system prompt，LLM 返回结构化 JSON，
     * JsonRepairUtil 修复后返回合法 JSON String。
     */
    public static void testCallSingleObject() {
        TestConfig.printTestHeader("测试 1：call() — 单对象结构化输出");

        ChatModel chatModel = TestConfig.createChatModel();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .maxRounds(3)
                .build();

        String query = "请推荐一本 Java 编程的经典书籍，包含书名、作者、出版年份和简要介绍";

        RunnableParams params = RunnableParams.builder()
                .conversationId(TestConfig.randomConvId())
                .outputType(BookInfo.class)
                .build();

        System.out.println("Q: " + query);
        String json = agent.call(query, params);

        System.out.println("A (raw JSON):\n" + json);

        // 反序列化验证
        try {
            BookInfo book = objectMapper.readValue(json, BookInfo.class);
            System.out.println("\n解析成功: " + book);
        } catch (Exception e) {
            System.err.println("解析失败: " + e.getMessage());
        }
    }

    /**
     * 测试 2：call() + OutputType.listOf(Class) — 输出 List 集合 JSON。
     *
     * <p>使用 OutputType.listOf() 处理泛型集合，框架生成对应的 JSON Array schema。
     */
    public static void testCallListObject() {
        TestConfig.printTestHeader("测试 2：call() — List 集合结构化输出");

        ChatModel chatModel = TestConfig.createChatModel();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .maxRounds(3)
                .build();

        String query = "请推荐 3 本不同领域的编程经典书籍，每本包含书名、作者、出版年份和简要介绍";

        RunnableParams params = RunnableParams.builder()
                .conversationId(TestConfig.randomConvId())
                .outputType(OutputType.listOf(BookInfo.class))
                .build();

        System.out.println("Q: " + query);
        String json = agent.call(query, params);

        System.out.println("A (raw JSON):\n" + json);

        // 反序列化验证
        try {
            List<BookInfo> books = objectMapper.readValue(json, new TypeReference<List<BookInfo>>() {});
            System.out.println("\n解析成功，共 " + books.size() + " 本:");
            books.forEach(b -> System.out.println("  - " + b));
        } catch (Exception e) {
            System.err.println("解析失败: " + e.getMessage());
        }
    }

    /**
     * 测试 3：callForResult() — 单对象 + 集合对象。
     *
     * <p>通过 callForResult() 获取 AgentResult，验证 Completed.answer() 返回合法 JSON。
     */
    public static void testCallResultStructured() {
        TestConfig.printTestHeader("测试 3：callForResult() — 单对象 + 集合对象");

        ChatModel chatModel = TestConfig.createChatModel();

        ReactAgent agent = ReactAgent.builder()
                .chatModel(chatModel)
                .maxRounds(3)
                .build();

        // 3a: 单对象
        System.out.println("--- 3a: callForResult() 单对象 ---");
        String query1 = "请推荐一本 Spring 框架的经典书籍，包含书名、作者、出版年份和简要介绍";

        RunnableParams params1 = RunnableParams.builder()
                .conversationId(TestConfig.randomConvId())
                .outputType(BookInfo.class)
                .build();

        System.out.println("Q: " + query1);
        AgentResult result1 = agent.callForResult(query1, params1);

        if (result1 instanceof AgentResult.Completed c) {
            System.out.println("A (raw JSON):\n" + c.answer());
            try {
                BookInfo book = objectMapper.readValue(c.answer(), BookInfo.class);
                System.out.println("解析成功: " + book);
            } catch (Exception e) {
                System.err.println("解析失败: " + e.getMessage());
            }
        }

        // 3b: 集合对象
        System.out.println("\n--- 3b: callForResult() 集合对象 ---");
        String query2 = "请推荐 3 本微服务架构相关的书籍，每本包含书名、作者、出版年份和简要介绍";

        RunnableParams params2 = RunnableParams.builder()
                .conversationId(TestConfig.randomConvId())
                .outputType(OutputType.listOf(BookInfo.class))
                .build();

        System.out.println("Q: " + query2);
        AgentResult result2 = agent.callForResult(query2, params2);

        if (result2 instanceof AgentResult.Completed c) {
            System.out.println("A (raw JSON):\n" + c.answer());
            try {
                List<BookInfo> books = objectMapper.readValue(c.answer(), new TypeReference<List<BookInfo>>() {});
                System.out.println("解析成功，共 " + books.size() + " 本:");
                books.forEach(b -> System.out.println("  - " + b));
            } catch (Exception e) {
                System.err.println("解析失败: " + e.getMessage());
            }
        }
    }

    // ===== Main =====

    public static void main(String[] args) {
        System.out.println("===============================================");
        System.out.println("       Structured Output Test");
        System.out.println("===============================================");
        System.out.println("ChatModel: " + TestConfig.CHAT_MODEL);
        System.out.println("===============================================");

        int testNumber = 2;

        switch (testNumber) {
            case 1 -> testCallSingleObject();
            case 2 -> testCallListObject();
            case 3 -> testCallResultStructured();
            default -> System.out.println("无效的测试编号: " + testNumber);
        }

        System.out.println("\n===============================================");
        System.out.println("       Test Completed");
        System.out.println("===============================================");
    }
}
