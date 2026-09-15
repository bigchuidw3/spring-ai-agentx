package com.agentx.ai.rag.parser.mineru;

import com.agentx.ai.rag.asset.DocumentAssetStore;
import com.agentx.ai.rag.common.ContentType;
import com.agentx.ai.rag.common.MetadataKeys;
import com.agentx.ai.rag.exception.RagErrorCode;
import com.agentx.ai.rag.exception.RagException;
import com.agentx.ai.rag.parser.DocumentParser;
import com.agentx.ai.rag.parser.ParsedDocument;
import com.agentx.ai.rag.parser.image.ChatModelImageDescriber;
import com.agentx.ai.rag.parser.image.ImageDescriber;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 基于 MinerU 精准解析 API 的多模态文档解析器。
 *
 * <p>适合 PDF、Office、图片和 HTML 中的复杂版式、表格、公式与图片混排场景。
 * 解析结果保留完整 Markdown，并额外输出图片内容块。
 *
 * @author bigchui
 */
public final class MineruDocumentParser implements DocumentParser {

    public static final String DEFAULT_BASE_URL = "https://mineru.net";
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMinutes(5);
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(3);
    public static final Duration DEFAULT_MAX_WAIT_TIME = Duration.ofMinutes(10);
    public static final int DEFAULT_IMAGE_WORKERS = 4;

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            ".pdf", ".doc", ".docx", ".ppt", ".pptx", ".xls", ".xlsx",
            ".png", ".jpg", ".jpeg", ".jp2", ".webp", ".gif", ".bmp", ".html", ".htm");

    private final MineruClient client;
    private final MineruResultParser resultParser;
    private final MineruImagePostProcessor imagePostProcessor;
    private final MineruModelVersion modelVersion;

    private MineruDocumentParser(Builder builder) {
        this.modelVersion = builder.modelVersion;
        ObjectMapper objectMapper = new ObjectMapper();
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(builder.requestTimeout)
                .build();
        MineruApiConfig config = new MineruApiConfig(
                builder.baseUrl,
                builder.authToken,
                builder.modelVersion,
                builder.language,
                builder.isOcr,
                builder.enableFormula,
                builder.enableTable,
                builder.pageRanges,
                builder.requestTimeout,
                builder.pollInterval,
                builder.maxWaitTime,
                httpClient
        );
        this.client = new MineruClient(config, objectMapper);
        this.resultParser = new MineruResultParser(objectMapper);
        this.imagePostProcessor = new MineruImagePostProcessor(
                builder.assetStore,
                imageDescriber(builder),
                builder.imageWorkers
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean supports(String fileName) {
        String extension = extension(fileName);
        return SUPPORTED_EXTENSIONS.contains(extension);
    }

    @Override
    public ParsedDocument parse(byte[] content, String fileName) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(fileName, "fileName");
        if (!supports(fileName)) {
            throw new RagException(RagErrorCode.UNSUPPORTED_DOCUMENT_TYPE,
                    "MinerU 不支持文件类型: " + fileName);
        }
        if (content.length == 0) {
            throw new RagException(RagErrorCode.DOCUMENT_PARSE_FAILED, "文件内容为空: " + fileName);
        }

        String dataId = UUID.randomUUID().toString();
        MineruClient.MineruParseResult result = client.parse(content, fileName, dataId);
        return imagePostProcessor.process(
                result.batchId(),
                new ParsedDocument(
                        fileName,
                        resultParser.parse(result.zipContent()),
                        metadata(fileName, result.batchId())
                ));
    }

    private LinkedHashMap<String, Object> metadata(String fileName, String batchId) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(MetadataKeys.FILE_NAME, fileName);
        metadata.put(MetadataKeys.CONTENT_TYPE, ContentType.TEXT.code());
        metadata.put("parserName", "MinerU");
        metadata.put("mineruModelVersion", modelVersion.code());
        metadata.put("mineruBatchId", batchId);
        return metadata;
    }

    private static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex).toLowerCase(Locale.ROOT);
    }

    private static ImageDescriber imageDescriber(Builder builder) {
        if (!builder.enableImageUnderstanding) {
            return null;
        }
        return builder.imageDescriber == null
                ? new ChatModelImageDescriber(builder.imageChatModel)
                : builder.imageDescriber;
    }

    public static final class Builder {

        private String baseUrl = DEFAULT_BASE_URL;
        private String authToken;
        private MineruModelVersion modelVersion = MineruModelVersion.VLM;
        private String language = "ch";
        private Boolean isOcr = false;
        private Boolean enableFormula = true;
        private Boolean enableTable = true;
        private String pageRanges;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        private Duration pollInterval = DEFAULT_POLL_INTERVAL;
        private Duration maxWaitTime = DEFAULT_MAX_WAIT_TIME;
        private boolean enableImageUnderstanding;
        private ChatModel imageChatModel;
        private ImageDescriber imageDescriber;
        private int imageWorkers = DEFAULT_IMAGE_WORKERS;
        private DocumentAssetStore assetStore;

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder authToken(String authToken) {
            this.authToken = authToken;
            return this;
        }

        public Builder enableImageUnderstanding(boolean enableImageUnderstanding) {
            this.enableImageUnderstanding = enableImageUnderstanding;
            return this;
        }

        public Builder imageChatModel(ChatModel imageChatModel) {
            this.imageChatModel = imageChatModel;
            return this;
        }

        public Builder imageDescriber(ImageDescriber imageDescriber) {
            this.imageDescriber = imageDescriber;
            return this;
        }

        public Builder imageWorkers(int imageWorkers) {
            this.imageWorkers = imageWorkers;
            return this;
        }

        public Builder assetStore(DocumentAssetStore assetStore) {
            this.assetStore = assetStore;
            return this;
        }

        public Builder modelVersion(MineruModelVersion modelVersion) {
            this.modelVersion = modelVersion;
            return this;
        }

        public Builder language(String language) {
            this.language = language;
            return this;
        }

        public Builder isOcr(boolean isOcr) {
            this.isOcr = isOcr;
            return this;
        }

        public Builder enableFormula(boolean enableFormula) {
            this.enableFormula = enableFormula;
            return this;
        }

        public Builder enableTable(boolean enableTable) {
            this.enableTable = enableTable;
            return this;
        }

        public Builder pageRanges(String pageRanges) {
            this.pageRanges = pageRanges;
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder pollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
            return this;
        }

        public Builder maxWaitTime(Duration maxWaitTime) {
            this.maxWaitTime = maxWaitTime;
            return this;
        }

        public MineruDocumentParser build() {
            validate();
            return new MineruDocumentParser(this);
        }

        private void validate() {
            requireText(baseUrl, "baseUrl");
            requireText(authToken, "authToken");
            requireText(language, "language");
            Objects.requireNonNull(modelVersion, "modelVersion");
            if (enableImageUnderstanding) {
                if (imageDescriber == null) {
                    Objects.requireNonNull(imageChatModel,
                            "开启图片语义理解时 imageChatModel 或 imageDescriber 不能为空");
                }
                if (imageDescriber != null && imageChatModel != null) {
                    throw new RagException(RagErrorCode.PARSER_CONFIG_INVALID,
                            "imageDescriber 与 imageChatModel 只能配置其中一个");
                }
            }
            requirePositive(imageWorkers, "imageWorkers");
            requirePositive(requestTimeout, "requestTimeout");
            requirePositive(pollInterval, "pollInterval");
            requirePositive(maxWaitTime, "maxWaitTime");
            if (pollInterval.compareTo(maxWaitTime) > 0) {
                throw new RagException(RagErrorCode.PARSER_CONFIG_INVALID,
                        "pollInterval 不能大于 maxWaitTime");
            }
            URI uri;
            try {
                uri = URI.create(baseUrl);
            } catch (IllegalArgumentException e) {
                throw new RagException(RagErrorCode.PARSER_CONFIG_INVALID,
                        "baseUrl 必须是合法 URL: " + baseUrl, e);
            }
            String scheme = uri.getScheme();
            if (!uri.isAbsolute() || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new RagException(RagErrorCode.PARSER_CONFIG_INVALID,
                        "baseUrl 必须是 http/https 绝对地址: " + baseUrl);
            }
        }

        private void requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new RagException(RagErrorCode.PARSER_CONFIG_INVALID, name + " 不能为空");
            }
        }

        private void requirePositive(Duration value, String name) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new RagException(RagErrorCode.PARSER_CONFIG_INVALID, name + " 必须大于 0");
            }
        }

        private void requirePositive(int value, String name) {
            if (value <= 0) {
                throw new RagException(RagErrorCode.PARSER_CONFIG_INVALID,
                        name + " 必须大于 0");
            }
        }
    }
}
