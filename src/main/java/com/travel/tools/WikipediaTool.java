package com.travel.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 工具：Wikipedia 查询（完全免费，无需API Key）
 * 用途：获取景点/城市的历史文化背景、详细介绍
 */
@Slf4j
@Component
public class WikipediaTool {

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(name = "wikipedia_query", description = """
            从Wikipedia查询景点/城市的历史文化背景、详细介绍。
            完全免费，无需API Key，无限调用。

            调用时机：
            - 用户询问景点的历史背景（如"故宫有多少年历史？"）
            - 用户想了解城市文化（如"西安有什么历史文化？"）
            - 需要丰富旅游计划的内容深度
            - enhanced_rag_query 和 search_place 无法提供详细信息时

            参数说明：
            - query：查询关键词，如"故宫"、"巴黎"、"寿司历史"
            - language：语言代码（可选，默认zh）
              * zh - 中文Wikipedia
              * en - 英文Wikipedia

            返回：详细的百科内容，包含历史、文化、特色等
            """)
    public String queryWikipedia(
            @ToolParam(description = "查询关键词，如'故宫'、'巴黎'、'寿司历史'") String query,
            @ToolParam(description = "语言代码：zh=中文(默认), en=英文", required = false) String language) {

        if (query == null || query.trim().isEmpty()) {
            return "❌ 查询关键词不能为空！";
        }

        String lang = (language != null && !language.trim().isEmpty()) ? language.trim() : "zh";

        try {
            // Step 1: 搜索条目
            String searchUrl = String.format(
                    "https://%s.wikipedia.org/w/api.php?action=query&list=search&srsearch=%s&format=json&srlimit=5",
                    lang,
                    URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
            );

            Request searchRequest = new Request.Builder().url(searchUrl).build();
            String pageTitle = null;

            try (Response searchResponse = httpClient.newCall(searchRequest).execute()) {
                if (!searchResponse.isSuccessful()) {
                    return String.format("❌ Wikipedia搜索失败，HTTP状态码：%d", searchResponse.code());
                }

                String searchBody = searchResponse.body().string();
                JsonNode searchJson = objectMapper.readTree(searchBody);
                JsonNode searchResults = searchJson.path("query").path("search");

                if (!searchResults.isArray() || searchResults.isEmpty()) {
                    return String.format("❌ Wikipedia未找到【%s】的相关条目。", query);
                }

                // 取第一个结果的标题
                pageTitle = searchResults.get(0).path("title").asText();
            }

            if (pageTitle == null) {
                return String.format("❌ 无法获取【%s】的Wikipedia条目。", query);
            }

            // Step 2: 获取条目详细内容
            String contentUrl = String.format(
                    "https://%s.wikipedia.org/w/api.php?action=query&titles=%s&prop=extracts&exintro=true&explaintext=true&format=json",
                    lang,
                    URLEncoder.encode(pageTitle, StandardCharsets.UTF_8)
            );

            Request contentRequest = new Request.Builder().url(contentUrl).build();
            try (Response contentResponse = httpClient.newCall(contentRequest).execute()) {
                if (!contentResponse.isSuccessful()) {
                    return String.format("❌ 获取Wikipedia内容失败，HTTP状态码：%d", contentResponse.code());
                }

                String contentBody = contentResponse.body().string();
                JsonNode contentJson = objectMapper.readTree(contentBody);
                JsonNode pages = contentJson.path("query").path("pages");
                JsonNode page = pages.elements().next();
                String extract = page.path("extract").asText();

                if (extract.isEmpty()) {
                    return String.format("❌ Wikipedia条目【%s】无详细内容。", pageTitle);
                }

                // 截断过长的内容（保留前1500字符）
                String summary = extract.length() > 1500 ? extract.substring(0, 1500) + "..." : extract;

                StringBuilder sb = new StringBuilder();
                sb.append("📚 Wikipedia：").append(pageTitle).append("\n\n");
                sb.append(summary).append("\n\n");
                sb.append("🔗 完整内容：https://").append(lang).append(".wikipedia.org/wiki/")
                        .append(pageTitle.replace(" ", "_")).append("\n");
                sb.append("💡 数据来源：Wikipedia（完全免费）");

                log.info("[WikipediaTool] 查询成功: query={}, title={}", query, pageTitle);
                return sb.toString();
            }

        } catch (Exception e) {
            log.error("[WikipediaTool] 查询失败: query={}", query, e);
            return "❌ Wikipedia查询失败：" + e.getMessage() + "。请稍后再试。";
        }
    }
}
