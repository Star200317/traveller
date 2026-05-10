package com.travel.tools;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 工具2：网页抓取（Jsoup）
 */
@Slf4j
@Component
public class WebScrapeTool {

    @Tool(description = "抓取指定网页的文本内容，用于获取旅游攻略、景点详情等详细信息")
    public String webScrape(
            @ToolParam(description = "要抓取的网页URL") String url) {
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(60000)
                    .get();
            // 去除脚本和样式，只取正文
            doc.select("script, style, nav, footer, header, .ad").remove();
            String text = doc.body().text();
            // 限制长度
            return text.length() > 3000 ? text.substring(0, 3000) + "..." : text;
        } catch (Exception e) {
            log.error("[WebScrape] 抓取失败: {}", url, e);
            return "网页抓取失败：" + e.getMessage();
        }
    }
}
