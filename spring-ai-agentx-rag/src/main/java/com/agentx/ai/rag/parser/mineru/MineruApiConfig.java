package com.agentx.ai.rag.parser.mineru;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * MinerU API 客户端配置。
 *
 * @author bigchui
 */
record MineruApiConfig(
        String baseUrl,
        String token,
        MineruModelVersion modelVersion,
        String language,
        Boolean isOcr,
        Boolean enableFormula,
        Boolean enableTable,
        String pageRanges,
        Duration requestTimeout,
        Duration pollInterval,
        Duration maxWaitTime,
        HttpClient httpClient
) {
}
