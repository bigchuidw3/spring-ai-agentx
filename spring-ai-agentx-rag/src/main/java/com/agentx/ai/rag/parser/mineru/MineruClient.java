package com.agentx.ai.rag.parser.mineru;

import com.agentx.ai.rag.exception.RagErrorCode;
import com.agentx.ai.rag.exception.RagException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * MinerU 精准解析 API 客户端。
 *
 * <p>只负责申请上传地址、上传字节、轮询任务和下载结果包，不解释业务结果。
 *
 * @author bigchui
 */
final class MineruClient {

    private static final String APPLY_UPLOAD_URL_PATH = "api/v4/file-urls/batch";
    private static final String RESULT_URL_PATH_PREFIX = "api/v4/extract-results/batch/";

    private final MineruApiConfig config;
    private final ObjectMapper objectMapper;

    MineruClient(MineruApiConfig config, ObjectMapper objectMapper) {
        this.config = Objects.requireNonNull(config, "config");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    MineruParseResult parse(byte[] content, String fileName, String dataId) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(fileName, "fileName");

        UploadTarget target = applyUploadUrl(fileName, dataId);
        upload(target.uploadUrl(), content);
        JsonNode result = waitForResult(target.batchId());
        String state = text(result, "state");
        if (!"done".equals(state)) {
            throw apiError("MinerU 任务未完成，state=" + state);
        }

        String resultUrl = text(result, "full_zip_url");
        if (resultUrl.isBlank()) {
            throw apiError("MinerU 任务完成但缺少 full_zip_url");
        }
        return new MineruParseResult(target.batchId(), download(resultUrl));
    }

    private UploadTarget applyUploadUrl(String fileName, String dataId) {
        MineruModelVersion modelVersion = resolveModelVersion(fileName);
        ObjectNode file = objectMapper.createObjectNode();
        file.put("name", fileName);
        file.put("data_id", dataId);

        ObjectNode request = objectMapper.createObjectNode();
        request.set("files", objectMapper.createArrayNode().add(file));
        request.put("model_version", modelVersion.code());
        if (modelVersion != MineruModelVersion.HTML) {
            request.put("language", config.language());
            putIfPresent(request, "enable_formula", config.enableFormula());
            putIfPresent(request, "enable_table", config.enableTable());
            putIfPresent(file, "is_ocr", config.isOcr());
            putIfPresent(file, "page_ranges", config.pageRanges());
        }

        JsonNode data = postJson(url(APPLY_UPLOAD_URL_PATH), request);
        String batchId = text(data, "batch_id");
        JsonNode urls = data.path("file_urls");
        if (batchId.isBlank() || !urls.isArray() || urls.isEmpty() || urls.get(0).isNull()) {
            throw apiError("MinerU 未返回有效上传地址");
        }
        return new UploadTarget(batchId, urls.get(0).asText());
    }

    private void upload(String uploadUrl, byte[] content) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(uploadUrl))
                .timeout(config.requestTimeout())
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                .build();
        send(request);
    }

    private JsonNode waitForResult(String batchId) {
        long deadline = System.nanoTime() + config.maxWaitTime().toNanos();
        while (true) {
            JsonNode result = extractResult(getJson(url(RESULT_URL_PATH_PREFIX + batchId)));
            String state = text(result, "state");
            if ("done".equals(state)) {
                return result;
            }
            if ("failed".equals(state)) {
                throw apiError("MinerU 解析失败: " + text(result, "err_msg"));
            }
            if (System.nanoTime() >= deadline) {
                throw apiError("MinerU 解析超时，最后状态=" + state);
            }
            sleep(config.pollInterval());
        }
    }

    private JsonNode extractResult(JsonNode data) {
        JsonNode results = data.path("extract_result");
        if (!results.isArray() || results.isEmpty()) {
            return objectMapper.createObjectNode().put("state", "pending");
        }
        return results.get(0);
    }

    private byte[] download(String resultUrl) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(resultUrl))
                .timeout(config.requestTimeout())
                .GET()
                .build();
        return send(request).body();
    }

    private JsonNode postJson(String url, ObjectNode body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(config.requestTimeout())
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.token())
                .POST(HttpRequest.BodyPublishers.ofByteArray(toBytes(body)))
                .build();
        return parseApiResponse(send(request)).path("data");
    }

    private JsonNode getJson(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(config.requestTimeout())
                .header("Authorization", "Bearer " + config.token())
                .header("Accept", "application/json")
                .GET()
                .build();
        return parseApiResponse(send(request)).path("data");
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        try {
            HttpResponse<byte[]> response = config.httpClient().send(
                    request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw apiError("MinerU HTTP " + response.statusCode() + ": "
                        + preview(response.body()));
            }
            return response;
        } catch (IOException e) {
            throw new RagException(RagErrorCode.DOCUMENT_PARSE_FAILED, "MinerU 请求失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RagException(RagErrorCode.DOCUMENT_PARSE_FAILED, "MinerU 请求被中断", e);
        }
    }

    private JsonNode parseApiResponse(HttpResponse<byte[]> response) {
        try {
            JsonNode body = objectMapper.readTree(response.body());
            int code = body.path("code").asInt(-1);
            if (code != 0) {
                throw apiError("MinerU API 错误 code=" + code
                        + ", msg=" + text(body, "msg"));
            }
            return body;
        } catch (IOException e) {
            throw new RagException(RagErrorCode.DOCUMENT_PARSE_FAILED,
                    "MinerU 返回无效 JSON: " + preview(response.body()), e);
        }
    }

    private MineruModelVersion resolveModelVersion(String fileName) {
        String lowerFileName = fileName.toLowerCase();
        return lowerFileName.endsWith(".html") || lowerFileName.endsWith(".htm")
                ? MineruModelVersion.HTML
                : config.modelVersion();
    }

    private void putIfPresent(ObjectNode node, String field, Boolean value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    private String url(String path) {
        String base = config.baseUrl().endsWith("/") ? config.baseUrl() : config.baseUrl() + "/";
        return URI.create(base).resolve(path).toString();
    }

    private byte[] toBytes(ObjectNode body) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new RagException(RagErrorCode.DOCUMENT_PARSE_FAILED, "序列化 MinerU 请求失败", e);
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RagException(RagErrorCode.DOCUMENT_PARSE_FAILED, "MinerU 任务轮询被中断", e);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText();
    }

    private RagException apiError(String message) {
        return new RagException(RagErrorCode.DOCUMENT_PARSE_FAILED, message);
    }

    private String preview(byte[] body) {
        if (body == null) {
            return "";
        }
        String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    private record UploadTarget(String batchId, String uploadUrl) {
    }

    record MineruParseResult(String batchId, byte[] zipContent) {
    }
}
