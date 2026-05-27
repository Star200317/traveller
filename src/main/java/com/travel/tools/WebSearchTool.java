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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .protocols(List.of(Protocol.HTTP_1_1))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(name = "web_search", description = """
            联网搜索最新旅游信息（Google Search API via Serper）。

            【重要】这是知识库查询失败后的降级方案！

            调用时机（按优先级）：
            1. 【必须】enhanced_rag_query 返回"知识库中暂无相关信息" → 立即调用此工具
            2. 查询实时信息：票价、开放时间、最新政策、天气预警
            3. 知识库信息不完整/过时 → 用此工具补充

            绝对禁止：
            - ❌ 不先查知识库就直接调用此工具
            - ❌ 知识库有信息时还调用此工具

            参数：
            - query: 搜索关键词（必填），要具体明确
              ✅ 正确："大理古城门票价格2024"
              ❌ 错误："大理"

            返回：每条结果包含标题、摘要、链接
            """)
    public String webSearch(
            @ToolParam(description = "搜索关键词，如'北京故宫门票价格2024'") String query) {
        try {
            // 用 ObjectMapper 安全构建 JSON，避免特殊字符导致格式错误
            Map<String, Object> requestMap = new HashMap<>();
            requestMap.put("q", query);
            requestMap.put("num", maxResults);
            requestMap.put("hl", "zh-cn");
            String json = objectMapper.writeValueAsString(requestMap);

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
                if (results.isEmpty()) {
                    return "搜索无结果，请尝试更换关键词。";
                }
                return String.join("\n\n---\n\n", results);
            }
        } catch (Exception e) {
            log.error("[WebSearch] 搜索失败: {}", query, e);
            return "【搜索暂时不可用】当前网络无法访问搜索服务。建议：1) 请稍后再试；2) 可尝试使用知识库查询（ragQuery），或更换关键词重试。";
        }
    }
}
