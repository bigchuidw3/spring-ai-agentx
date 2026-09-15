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
 * 基于 HTTP 调用的重排器。
 *
 * 请求格式兼容 Jina/Cohere 风格的 rerank API：{model, query, documents, top_n}，
 * 响应解析 {results: [{index, relevance_score}]}，按相关性分数降序取 topK。
 *
 * @author bigchui
 */
public final class HttpReranker implements Reranker {

    private final String url;
    private final String apiKey;
    private final String model;
    private final int topK;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpReranker(String url, String apiKey, String model) {
        this(url, apiKey, model, 3);
    }

    public HttpReranker(String url, String apiKey, String model, int topK) {
        this.url = Objects.requireNonNull(url, "url");
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = Objects.requireNonNull(model, "model");
        this.topK = topK;
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

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("query", query);
        ArrayNode documents = body.putArray("documents");
        for (Document doc : candidates) {
            documents.add(doc.getText());
        }
        body.put("top_n", topK);

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
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

    private List<Document> parse(byte[] body, List<Document> candidates) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode results = root.path("results");
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
