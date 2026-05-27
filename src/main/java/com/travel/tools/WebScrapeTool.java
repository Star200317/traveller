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

    @Tool(name = "web_scrape", description = """
            抓取指定网页的文本内容（Jsoup实现）。

            【慎用】优先用 web_search 获取摘要，只在需要时用此工具！

            调用时机：
            - web_search 找到相关文章，需要详细内容时
            - 用户明确要求抓取某个网页时
            - 需要获取网页正文的完整内容时

            绝对禁止：
            - ❌ 不先调用 web_search 就直接调用此工具
            - ❌ 对用户提供的每个URL都调用此工具

            参数：
            - url: 要抓取的网页URL（必填）

            返回：网页正文文本（最多3000字符）
            """)
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
