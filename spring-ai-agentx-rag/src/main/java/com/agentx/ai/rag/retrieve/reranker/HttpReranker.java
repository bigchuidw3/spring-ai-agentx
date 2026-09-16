package com.agentx.ai.rag.retrieve.reranker;

import com.agentx.ai.rag.exception.RagErrorCode;
import com.agentx.ai.rag.exception.RagException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 基于 HTTP 调用的通用重排器。
 *
 * 通过 baseUrl 接入任意 rerank 服务，用 requestFormat 适配不同请求体格式。
 * 响应兼容 results 与 output.results 两种结构，按相关性分数降序取 topK。
 *
 * @author bigchui
 */
public final class HttpReranker implements Reranker {

    /**
     * 请求体格式。
     */
    public enum RequestFormat {

        /**
         * 平铺格式：{model, query, documents, top_n}，Jina / Cohere / OpenAI 兼容。
         */
        FLAT,

        /**
         * 嵌套格式：{model, input: {query, documents}, parameters: {top_n}}，DashScope。
         */
        DASHSCOPE
    }

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int topK;
    private final RequestFormat requestFormat;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpReranker(String baseUrl, String apiKey, String model, int topK) {
        this(baseUrl, apiKey, model, topK, RequestFormat.FLAT);
    }

    public HttpReranker(String baseUrl, String apiKey, String model, int topK, RequestFormat requestFormat) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = Objects.requireNonNull(model, "model");
        this.topK = topK;
        this.requestFormat = Objects.requireNonNull(requestFormat, "requestFormat");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public List<Document> rerank(String query, List<Document> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        ObjectNode body = buildBody(query, candidates);

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(toBytes(body)));
        if (!apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }

        try {
            HttpResponse<byte[]> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RagException(RagErrorCode.RETRIEVE_FAILED,
                        "rerank HTTP " + response.statusCode() + ": " + preview(response.body()));
            }
            return parse(response.body(), candidates);
        } catch (IOException e) {
            throw new RagException(RagErrorCode.RETRIEVE_FAILED, "rerank 请求失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RagException(RagErrorCode.RETRIEVE_FAILED, "rerank 请求被中断", e);
        }
    }

    private ObjectNode buildBody(String query, List<Document> candidates) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        if (requestFormat == RequestFormat.DASHSCOPE) {
            ObjectNode input = body.putObject("input");
            input.put("query", query);
            ArrayNode documents = input.putArray("documents");
            for (Document doc : candidates) {
                documents.add(doc.getText());
            }
            body.putObject("parameters").put("top_n", topK);
        } else {
            body.put("query", query);
            ArrayNode documents = body.putArray("documents");
            for (Document doc : candidates) {
                documents.add(doc.getText());
            }
            body.put("top_n", topK);
        }
        return body;
    }

    private List<Document> parse(byte[] body, List<Document> candidates) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode results = root.path("results");
        if (results.isMissingNode() || !results.isArray()) {
            results = root.path("output").path("results");
        }
        List<RankedResult> ranked = new ArrayList<>();
        for (JsonNode result : results) {
            int index = result.path("index").asInt(-1);
            double score = result.path("relevance_score").asDouble(0.0);
            if (index >= 0 && index < candidates.size()) {
                ranked.add(new RankedResult(index, score));
            }
        }
        ranked.sort(Comparator.comparingDouble(RankedResult::score).reversed());

        List<Document> output = new ArrayList<>(Math.min(topK, ranked.size()));
        for (int i = 0; i < Math.min(topK, ranked.size()); i++) {
            output.add(candidates.get(ranked.get(i).index()));
        }
        return output;
    }

    private byte[] toBytes(ObjectNode body) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new RagException(RagErrorCode.RETRIEVE_FAILED, "序列化 rerank 请求失败", e);
        }
    }

    private String preview(byte[] body) {
        if (body == null) {
            return "";
        }
        String text = new String(body, StandardCharsets.UTF_8);
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    private record RankedResult(int index, double score) {
    }
}
