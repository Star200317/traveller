package com.travel.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具1：联网搜索（Serper API）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSearchTool {

    @Value("${serper.api-key}")
    private String serperApiKey;

    @Value("${serper.base-url}")
    private String baseUrl;

    @Value("${serper.max-results:5}")
    private int maxResults;

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(description = "联网搜索最新旅游信息，包括景点详情、交通、住宿、天气、门票价格等实时信息")
    public String webSearch(
            @ToolParam(description = "搜索关键词，如'北京故宫门票价格2024'") String query) {
        try {
            String json = String.format("{\"q\":\"%s\",\"num\":%d,\"hl\":\"zh-cn\"}", query, maxResults);
            RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url(baseUrl + "/search")
                    .addHeader("X-API-KEY", serperApiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body().string();
                JsonNode root = objectMapper.readTree(respBody);
                JsonNode organic = root.get("organic");
                List<String> results = new ArrayList<>();
                if (organic != null) {
                    for (int i = 0; i < Math.min(organic.size(), maxResults); i++) {
                        JsonNode item = organic.get(i);
                        results.add(String.format("标题：%s\n摘要：%s\n链接：%s",
                                item.get("title").asText(),
                                item.has("snippet") ? item.get("snippet").asText() : "",
                                item.get("link").asText()));
                    }
                }
                return String.join("\n\n---\n\n", results);
            }
        } catch (Exception e) {
            log.error("[WebSearch] 搜索失败: {}", query, e);
            return "搜索失败：" + e.getMessage();
        }
    }
}
