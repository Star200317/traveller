package com.travel.service;

import com.travel.entity.Message;
import com.travel.tools.BaiduTranslateTool;
import com.travel.tools.CurrencyTool;
import com.travel.tools.EnhancedRagQueryTool;
import com.travel.tools.OpenMeteoWeatherTool;
import com.travel.tools.OverpassApiTool;
import com.travel.tools.SearchPlaceTool;
import com.travel.tools.WeatherTool;
import com.travel.tools.WebScrapeTool;
import com.travel.tools.WebSearchTool;
import com.travel.tools.WikipediaTool;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ChatService {

    /** 日志器 */
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    /** 判定“结果有效”的最小长度阈值（过短通常代表检索失败或信息不足） */
    private static final int MIN_EFFECTIVE_CONTEXT_LEN = 120;
    /** 从用户输入中提取 URL 的正则 */
    private static final Pattern URL_PATTERN = Pattern.compile("(https?://\\S+)");

    private static final Set<String> RAG_TRIGGER_WORDS = Set.of(
            "景点", "好玩", "玩的地方", "去哪玩", "旅游胜地", "打卡", "值得去",
            "好吃", "美食", "餐厅", "小吃", "吃什么", "特色菜", "美食街",
            "住哪", "酒店", "民宿", "客栈", "住宿", "青旅",
            "推荐", "攻略", "吃喝玩乐", "有什么"
    );

    private static final Set<String> FOOD_KEYWORDS = Set.of(
            "好吃", "美食", "餐厅", "小吃", "吃什么", "特色菜", "美食街", "饭店"
    );

    private static final Set<String> HOTEL_KEYWORDS = Set.of(
            "住哪", "酒店", "民宿", "客栈", "住宿", "青旅"
    );

    private static final Set<String> ATTRACTION_KEYWORDS = Set.of(
            "景点", "好玩", "玩的地方", "去哪玩", "打卡", "旅游", "景区", "游玩"
    );

    private static final String NO_KNOWLEDGE = "知识库中暂无相关信息";

    private static final String SYSTEM_PROMPT = """
            你是“旅小智”，一个专业但自然的旅游助手。
            规则：
            1. 用户问景点、美食、住宿、推荐时，优先参考系统注入的检索或工具结果。
            2. 如果系统注入的是联网结果，也必须优先基于该结果作答。
            3. 不要编造地址、价格、营业时间等事实信息。
            4. 回答简洁自然，不要写成报告。
            5. 若系统上下文不足，可继续调用工具补充信息。
            """;

    private final ChatModel chatModel;
    private final ConversationService conversationService;
    private final WebSearchTool webSearchTool;
    private final WebScrapeTool webScrapeTool;
    private final EnhancedRagQueryTool enhancedRagQueryTool;
    private final EnhancedRagService enhancedRagService;
    private final SearchPlaceTool searchPlaceTool;
    private final WeatherTool weatherTool;
    private final CurrencyTool currencyTool;
    private final WikipediaTool wikipediaTool;
    private final BaiduTranslateTool baiduTranslateTool;
    private final OverpassApiTool overpassApiTool;
    private final OpenMeteoWeatherTool openMeteoWeatherTool;

    /**
     * 是否需要在主对话前进行 RAG 预检。
     * 规则：输入非空、长度足够、且命中 RAG 触发关键词。
     */
    private boolean shouldPreQueryRag(String input) {
        if (input == null || input.isBlank()) return false;
        String normalized = input.trim().toLowerCase();
        if (normalized.length() < 2) return false;
        return containsAny(normalized, RAG_TRIGGER_WORDS);
    }

    /**
     * 基于关键词推断查询类别，供 RAG / 工具检索使用。
     * 返回值：food / hotel / attraction / all
     */
    private String inferCategory(String input) {
        String normalized = input == null ? "" : input.toLowerCase();
        if (containsAny(normalized, FOOD_KEYWORDS)) return "food";
        if (containsAny(normalized, HOTEL_KEYWORDS)) return "hotel";
        if (containsAny(normalized, ATTRACTION_KEYWORDS)) return "attraction";
        return "all";
    }

    /** 判断文本是否包含任意关键词 */
    private boolean containsAny(String text, Set<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    /** 预检知识库（Enhanced RAG），失败时返回 null */
    private String preQueryKnowledgeBase(String query, String category) {
        try {
            log.info("[Chat] Trigger RAG pre-query: query={}, category={}", query, category);
            String result = enhancedRagService.enhancedQuery(query, category, null, 5, true);
            log.info("[Chat] RAG pre-query done, length={}", result == null ? 0 : result.length());
            return result;
        } catch (Exception e) {
            log.error("[Chat] RAG pre-query failed", e);
            return null;
        }
    }

    /** 预检联网搜索兜底，失败时返回 null */
    private String preQueryWebFallback(String query) {
        try {
            log.info("[Chat] Trigger web fallback: query={}", query);
            String result = webSearchTool.webSearch(query);
            log.info("[Chat] Web fallback done, length={}", result == null ? 0 : result.length());
            return result;
        } catch (Exception e) {
            log.error("[Chat] Web fallback failed", e);
            return null;
        }
    }

    /** 判定知识库结果是否“命中不足/无效” */
    private boolean isKnowledgeMiss(String text) {
        if (text == null) return true;
        String normalized = text.trim();
        if (normalized.isBlank()) return true;
        if (normalized.contains(NO_KNOWLEDGE)) return true;
        return normalized.length() < MIN_EFFECTIVE_CONTEXT_LEN
                || normalized.contains("暂无相关")
                || normalized.contains("未找到相关")
                || normalized.contains("没有相关");
    }

    /** 判定 web fallback 结果是否“命中不足/无效” */
    private boolean isWebFallbackMiss(String text) {
        if (text == null) return true;
        String normalized = text.trim();
        if (normalized.isBlank()) return true;
        return normalized.length() < 60
                || normalized.contains("暂无相关")
                || normalized.contains("未找到相关")
                || normalized.contains("搜索失败")
                || normalized.toLowerCase().contains("error");
    }

    /**
     * 判定工具调用结果是否无效：
     * - 明确报错关键词
     * - 本地库无数据提示
     * - 常见“未找到/无结果/查询失败”语义
     */
    private boolean isToolResultMiss(String text) {
        if (text == null) return true;
        String normalized = text.trim();
        if (normalized.isBlank()) return true;

        String lower = normalized.toLowerCase();
        if (lower.contains("error")
                || lower.contains("failed")
                || lower.contains("not found")
                || normalized.startsWith("❌")
                || normalized.startsWith("ERROR")) {
            return true;
        }

        if ((normalized.contains("本地地点库") && normalized.contains("暂无"))
                || (normalized.contains("请使用联网搜索工具") && normalized.contains("获取信息"))
                || (lower.contains("local") && lower.contains("no data"))) {
            return true;
        }

        return normalized.contains("暂无相关")
                || normalized.contains("未找到")
                || normalized.contains("无结果")
                || normalized.contains("搜索失败")
                || normalized.contains("查询失败");
    }

    /** 是否属于历史/文化/百科类问题（优先尝试 wikipedia） */
    private boolean isCultureIntent(String input) {
        String text = input == null ? "" : input;
        return text.contains("历史") || text.contains("文化") || text.contains("起源")
                || text.contains("由来") || text.contains("百科") || text.contains("背景");
    }

    /** 是否属于地点推荐类问题（触发地点工具链） */
    private boolean isPlaceIntent(String input) {
        String text = input == null ? "" : input;
        return text.contains("景点") || text.contains("推荐") || text.contains("酒店")
                || text.contains("民宿") || text.contains("美食") || text.contains("餐厅");
    }

    /** 从用户意图推断地点类型：hotel / restaurant / attraction */
    private String inferPlaceType(String input) {
        String text = input == null ? "" : input;
        if (text.contains("酒店") || text.contains("民宿")) return "hotel";
        if (text.contains("美食") || text.contains("餐厅") || text.contains("小吃")) return "restaurant";
        return "attraction";
    }

    /**
     * 从用户输入中提取城市名。
     * 仅用于“地点推荐类”硬路由，避免把“几个/适合夏天”等词误识别为城市。
     */
    private String extractCity(String input) {
        if (input == null || input.isBlank()) return null;
        String text = input.trim();
        String[] tails = {"景点推荐", "景点", "旅游", "旅行", "酒店", "民宿", "美食", "餐厅", "攻略"};
        for (String tail : tails) {
            int idx = text.indexOf(tail);
            if (idx > 0) {
                String candidate = text.substring(0, idx)
                        .replace("请推荐", "")
                        .replace("推荐", "")
                        .trim();
                if (candidate.contains("几个") || candidate.contains("适合") || candidate.contains("夏天") || candidate.contains("国外")) {
                    continue;
                }
                if (candidate.length() >= 2 && candidate.length() <= 12) return candidate;
            }
        }
        Matcher m = Pattern.compile("去([\\u4e00-\\u9fa5A-Za-z]{2,12})").matcher(text);
        if (m.find()) return m.group(1);
        return null;
    }

    /** 提取用户文本中的第一个 URL（用于网页抓取硬路由） */
    private String extractFirstUrl(String input) {
        if (input == null) return null;
        Matcher m = URL_PATTERN.matcher(input);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 工具硬路由入口（优先级高于普通 RAG 预检）：
     * 1) URL -> web_scrape -> web_search
     * 2) 文化类 -> wikipedia -> web_search
     * 3) 地点类 -> search_place -> overpass -> enhanced_rag_query -> web_search
     * 返回值是可注入到 system prompt 的工具上下文文本，未命中返回 null。
     */
    private String routeToolContext(String userInput) {
        try {
            String url = extractFirstUrl(userInput);
            if (url != null) {
                log.info("[Chat] Hard-route: web_scrape url={}", url);
                String scrape = webScrapeTool.webScrape(url);
                if (!isToolResultMiss(scrape)) return "【工具结果: web_scrape】\n" + scrape;
                String web = preQueryWebFallback(userInput);
                return isWebFallbackMiss(web) ? null : "【工具结果: web_search】\n" + web;
            }

            if (isCultureIntent(userInput)) {
                log.info("[Chat] Hard-route: wikipedia_query");
                String wiki = wikipediaTool.queryWikipedia(userInput, "zh");
                if (!isToolResultMiss(wiki)) return "【工具结果: wikipedia_query】\n" + wiki;
                String web = preQueryWebFallback(userInput);
                return isWebFallbackMiss(web) ? null : "【工具结果: web_search】\n" + web;
            }

            if (isPlaceIntent(userInput)) {
                String city = extractCity(userInput);
                String type = inferPlaceType(userInput);

                if (city == null) {
                    log.info("[Chat] Place intent without reliable city, fallback web_search query={}", userInput);
                    String web = preQueryWebFallback(userInput);
                    return isWebFallbackMiss(web) ? null : "【工具结果: web_search】\n" + web;
                }

                log.info("[Chat] Hard-route: search_place city={}, type={}", city, type);
                String local = searchPlaceTool.searchPlaces(city, type);
                boolean localMiss = isToolResultMiss(local);
                log.info("[Chat] search_place miss={} city={}, type={}", localMiss, city, type);
                if (!localMiss) return "【工具结果: search_place】\n" + local;

                String overpassType = "attraction".equals(type) ? "tourist_attraction" : type;
                log.info("[Chat] Fallback overpass_query city={}, type={}", city, overpassType);
                String overpass = overpassApiTool.queryPlaces(city, overpassType);
                boolean overpassMiss = isToolResultMiss(overpass);
                log.info("[Chat] overpass_query miss={} city={}, type={}", overpassMiss, city, overpassType);
                if (!overpassMiss) return "【工具结果: overpass_query】\n" + overpass;

                log.info("[Chat] search_place + overpass_query miss, fallback enhanced_rag_query query={}", userInput);
                String ragToolResult = enhancedRagQueryTool.enhancedQuery(
                        userInput,
                        inferCategory(userInput),
                        "enhanced",
                        null,
                        null
                );
                boolean ragToolMiss = isToolResultMiss(ragToolResult) || isKnowledgeMiss(ragToolResult);
                log.info("[Chat] enhanced_rag_query miss={} query={}", ragToolMiss, userInput);
                if (!ragToolMiss) return "【工具结果: enhanced_rag_query】\n" + ragToolResult;

                log.info("[Chat] All place tools miss, fallback web_search query={}", userInput);
                String web = preQueryWebFallback(userInput);
                return isWebFallbackMiss(web) ? null : "【工具结果: web_search】\n" + web;
            }
        } catch (Exception e) {
            log.warn("[Chat] Hard-route failed, fallback normal flow: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 对话主流程：
     * 1. 入库用户消息 + 自动更新会话标题
     * 2. 尝试工具硬路由
     * 3. 若未命中，再做 RAG 预检；RAG miss 再走 web_search；都 miss 则模型自主回答
     * 4. 组装 system prompt 与历史上下文
     * 5. 发起流式对话并保存 assistant 回复
     */
    public Flux<String> chat(Long conversationId, String userInput) {
        // 1) 记录本次请求：会话ID + 用户输入
        log.info("[Chat] New request conversationId={}, userInput={}", conversationId, userInput);

        // 2) 先把用户消息入库，并尝试自动更新会话标题
        conversationService.saveMessage(conversationId, "user", userInput);
        conversationService.autoUpdateTitle(conversationId);

        // 3) 先走“硬路由工具链”（如 URL 抓取、地点类工具链等）
        //    命中则返回工具上下文字符串；未命中返回 null
        String routedToolContext = routeToolContext(userInput);
        String ragContext = null;
        String webFallbackContext = null;

        // 4) 仅当硬路由未命中时，才考虑 RAG 预检
        //    顺序：RAG ->（miss）web_search ->（仍 miss）模型自主回答
        if (routedToolContext == null && shouldPreQueryRag(userInput)) {
            String category = inferCategory(userInput);
            ragContext = preQueryKnowledgeBase(userInput, category);
            if (isKnowledgeMiss(ragContext)) {
                log.info("[Chat] Knowledge miss, fallback to web_search query={}", userInput);
                ragContext = null;
                webFallbackContext = preQueryWebFallback(userInput);
                if (isWebFallbackMiss(webFallbackContext)) {
                    log.info("[Chat] Web fallback miss, model will answer by itself query={}", userInput);
                    webFallbackContext = null;
                }
            }
        }

        // 5) 组装上下文窗口历史（最近 N 轮对话）
        List<Message> history = conversationService.getContextWindow(conversationId);
        List<org.springframework.ai.chat.messages.Message> springMessages = new ArrayList<>();

        // 6) 组装 system prompt，并按优先级注入外部上下文：
        //    硬路由工具结果 > RAG 结果 > 联网兜底结果
        String systemPrompt = SYSTEM_PROMPT;
        if (routedToolContext != null && !routedToolContext.isBlank()) {
            systemPrompt += "\n\n" + routedToolContext + "\n";
            log.info("[Chat] Injected hard-route tool context");
        } else if (ragContext != null && !ragContext.isBlank()) {
            systemPrompt += "\n\n【知识库查询结果】\n" + ragContext + "\n【End】";
            log.info("[Chat] Injected RAG context");
        } else if (webFallbackContext != null && !webFallbackContext.isBlank()) {
            systemPrompt += "\n\n【联网搜索降级结果】\n" + webFallbackContext + "\n【End】";
            log.info("[Chat] Injected web fallback context");
        }
        springMessages.add(new SystemMessage(systemPrompt));

        // 7) 把历史消息转换为 Spring AI 消息结构
        for (Message msg : history) {
            if ("user".equals(msg.getRole())) {
                springMessages.add(new UserMessage(msg.getContent()));
            } else if ("assistant".equals(msg.getRole())) {
                springMessages.add(new AssistantMessage(msg.getContent()));
            }
        }

        StringBuilder fullResponse = new StringBuilder();

        return ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .build()
                .prompt()
                .messages(springMessages)
                .user(userInput)
                .tools(
                        webSearchTool,
                        webScrapeTool,
                        enhancedRagQueryTool,
                        searchPlaceTool,
                        weatherTool,
                        openMeteoWeatherTool,
                        currencyTool,
                        wikipediaTool,
                        baiduTranslateTool,
                        overpassApiTool
                )
                .stream()
                .content()
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1)).filter(this::isNetworkError))
                .doOnNext(fullResponse::append)
                .doOnComplete(() -> {
                    conversationService.saveMessage(conversationId, "assistant", fullResponse.toString());
                    log.info("[Chat] Done conversationId={}, responseLength={}", conversationId, fullResponse.length());
                })
                .doOnError(e -> log.error("[Chat] Error conversationId={}", conversationId, e));
    }

    /** 网络类错误判定（用于流式请求重试过滤） */
    private boolean isNetworkError(Throwable throwable) {
        String msg = throwable.getMessage();
        if (msg == null) return false;
        msg = msg.toLowerCase();
        return msg.contains("connection reset")
                || msg.contains("connection refused")
                || msg.contains("connection abort")
                || msg.contains("timeout")
                || msg.contains("read timed out")
                || msg.contains("broken pipe")
                || msg.contains("peer closed")
                || msg.contains("connection closed");
    }
}
