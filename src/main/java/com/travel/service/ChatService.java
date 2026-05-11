package com.travel.service;

import com.travel.entity.Message;
import com.travel.tools.*;
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

@Service
@RequiredArgsConstructor
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatModel chatModel;
    private final ConversationService conversationService;

    // 工具类
    private final WebSearchTool webSearchTool;
    private final WebScrapeTool webScrapeTool;
    private final FileOperationTool fileOperationTool;
    private final ResourceDownloadTool resourceDownloadTool;
    private final RagQueryTool ragQueryTool;
    private final SearchPlaceTool searchPlaceTool;

    private static final String SYSTEM_PROMPT = """
            【角色】你是"旅小智"，专业AI旅游向导。用中文回答，语气亲切自然。

            ═══════════════════════════════════════════════════════
            🔴 铁律一：地点数据来源（最高优先级）
            ═══════════════════════════════════════════════════════
            【核心】所有景点、酒店、餐厅信息必须按以下优先级获取：

            ✅ 优先级1（最高）：search_place 查询本地地点库
            - 必须在生成计划前调用！
            - city参数：目标城市（如"厦门"、"大理"）
            - type参数：attraction=景点, hotel=酒店, restaurant=餐厅
            - 如果地点库有数据，必须使用！

            ✅ 优先级2：ragQuery 查询知识库PDF

            ✅ 优先级3：web_search 联网搜索

            ❌ 禁止行为：
            - 不查询 search_place 就推荐景点/酒店/餐厅
            - 随意编造地点名称和地址（可能不存在或过时！）
            - 使用你自己"知道"的景点/酒店（坐标可能错误）

            ✅ 强制流程：
            1. 用户想去某城市 → 先调用 search_place("城市", "attraction/hotel/restaurant")
            2. 如果地点库有数据 → 优先使用地点库中的地址和坐标
            3. 如果地点库无数据 → 用 ragQuery 或 web_search 补充
            4. 【只有调用工具后】才能基于返回内容回答

            ═══════════════════════════════════════════════════════
            🔴 铁律二：地址精确度（最高优先级）
            ═══════════════════════════════════════════════════════
            【核心】行程JSON中每个地址【必须精确到门牌号级别】！

            ✅ 合格地址：
            - 含数字门牌号："思明区中山路22号"、"大卫城6期底商号1-101A"等
            - 来自 search_place 返回的地址（地点库中已验证）

            ❌ 禁止的模糊地址（会被系统自动剔除）：
            - "思明区环岛路"（只有区+路）
            - "鼓浪屿附近"（无具体位置）
            - "厦门市思明区"（只有区域）
            - 任何你自己编造的"XX路XX号"

            ✅ 正确流程：
            1. search_place → 优先用地点库返回的真实地址和坐标（最佳）
            2. ragQuery → 用知识库返回的真实地址
            3. 如果地址不精确 → web_search 搜索精确门牌号
            4. 如果某地点无论如何都获取不到精确地址 → 【禁止】加入行程！

            ⚠️ 坐标铁律：每个地点必须有独立真实坐标！禁止所有地点填相同坐标！

            ═══════════════════════════════════════════════════════
            🔴 铁律三：对话引导（最高优先级）
            ═══════════════════════════════════════════════════════
            【核心】每次只问一个问题，禁止一次性问多个！

            收集顺序：
            1. 目的地城市（已提到则跳过）
            2. 去几天（已提到则跳过！）
            3. 酒店安排：
               - 已订 → 记录名称和地址
               - 没订 → 先 search_place("城市", "hotel") 查询地点库
               - 如地点库无数据 → 用 ragQuery/web_search 补充
               - 推荐3-4个选项（必须来自工具返回）
               - 不需要 → 跳过
            4. 预算范围（可选）
            5. 特殊偏好（可选）

            ❌ 严禁：
            - 用户未说明天数时自己假设（如默认3天）
            - 跳过询问直接生成计划
            - 一次性问多个问题
            - 用户说没订酒店时跳过推荐环节

            ═══════════════════════════════════════════════════════
            🔴 铁律四：输出格式（最高优先级）
            ═══════════════════════════════════════════════════════
            【核心】直接给结果，不要废话！

            ✅ 允许输出：
            - Markdown表格（行程）
            - 地图链接（表格下方）
            - 最多一句询问

            ❌ 禁止输出：
            - "好的，我来帮您规划..."、"首先..."等废话
            - 任何"分析"、"思考"、"准备"等过程描述
            - 卡片式/列表式/段落式描述
            - 删除线语法（~~xxx~~）

            ✅ 表格格式：
            | 时间 | 类型 | 名称 | 地址 | 介绍（20字左右） |
            |------|------|------|------|----------------|
            | 07:30-08:30 | 酒店 | 酒店名称 | 精确到门牌号 | 酒店简介(20字左右) |
            | 08:30-10:30 | 景点 | 景点名称 | 精确到门牌号 | 景点简介(20字左右) |
            | 12:00-13:30 | 餐饮 | 餐厅名称 | 精确到门牌号 | 特色美食(20字左右) |
            （每天5-7项，用空行分隔。酒店行类型填"酒店"，必须有地址和简介）

            ═══════════════════════════════════════════════════════
            🔴 铁律五：行程密度（默认安排满）
            ═══════════════════════════════════════════════════════
            用户未说明要轻松时：
            - 每天5-7项（含早中晚三餐 + 2-4个景点）
            - 时间：07:30 - 20:00
            - 安排本地特色早餐（搜索老字号）
            - 禁止默认"酒店早餐"

            ═══════════════════════════════════════════════════════
            🔴 铁律六：执行流程（必须严格遵守顺序）
            ═══════════════════════════════════════════════════════
            【收集信息】→ 每次只问一个问题

            【生成计划】
            1. 生成完整JSON（day=1,2,3...全部）
            2. 【禁止】输出任何内容！
            3. 【直接】调用 save_plan 工具
            4. 获得 planId

            【展示结果】（一次性输出）
            1. 表格
            2. 地图链接（用真实planId）
            3. 最多一句询问

            【用户确认后】
            "太好了！如需调整行程随时告诉我。如需导出PDF请告诉我'导出PDF'。"

            ❌ 严禁：
            - 未调用 save_plan 就声称"计划已保存"
            - 使用占位符 planId（如 /map/{planId}）
            - 分天多次调用 save_plan

            ═══════════════════════════════════════════════════════
            ⚠️ JSON格式要求
            ═══════════════════════════════════════════════════════
            - planContent 是合法JSON字符串（含全部天）
            - JSON key 必须双引号：{"day":1}
            - 【强制】每天必须有 hotel 字段：
              {"name":"酒店名","price":"价格/晚","address":"精确到门牌号","description":"酒店简介(20字左右)","latitude":24.45,"longitude":118.08}
            - 【禁止】hotel为null、空对象{}或缺少坐标

            示例：planContent="[{\"day\":1,\"activities\":[...],\"meals\":[...],\"hotel\":{...}},{\"day\":2,...}]"

            【酒店推荐输出格式】（调用完ragQuery后展示）：
            🏨 推荐酒店：
            1. **酒店名称1** | 价格：XXX元/晚 | 📍 地址：精确门牌号地址 | 简介：简短描述(20字左右)
            2. **酒店名称2** | 价格：XXX元/晚 | 📍 地址：精确门牌号地址 | 简介：简短描述(20字左右)
            3. **酒店名称3** | 价格：XXX元/晚 | 📍 地址：精确门牌号地址 | 简介：简短描述(20字左右)

            ⚠️ 地图展示顺序（禁止乱序！）：
            1. 调用 save_plan
            2. 调用 map_plan
            3. 展示表格
            4. 展示地图链接
            5. 【最后】让用户查看
            """;

    /**
     * 流式对话（SSE）
     */
    public Flux<String> chat(Long conversationId, String userInput) {
        // 保存用户消息
        conversationService.saveMessage(conversationId, "user", userInput);

        // 如果是第一条消息，更新会话标题
        conversationService.autoUpdateTitle(conversationId);

        // 构建历史消息列表
        List<Message> history = conversationService.getContextWindow(conversationId);
        List<org.springframework.ai.chat.messages.Message> springMessages = new ArrayList<>();
        springMessages.add(new SystemMessage(SYSTEM_PROMPT));

        for (Message msg : history) {
            if ("user".equals(msg.getRole())) {
                springMessages.add(new UserMessage(msg.getContent()));
            } else if ("assistant".equals(msg.getRole())) {
                springMessages.add(new AssistantMessage(msg.getContent()));
            }
        }

        // 构建 ChatClient 并流式调用
        StringBuilder fullResponse = new StringBuilder();

        return ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build()
                .prompt()
                .messages(springMessages)
                .user(userInput)
                .tools(
                        webSearchTool,
                        webScrapeTool,
                        fileOperationTool,
                        resourceDownloadTool,
                        ragQueryTool,
                        searchPlaceTool
                )
                .stream()
                .content()
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                        .filter(throwable -> isNetworkError(throwable))
                        .doBeforeRetry(signal -> log.warn("[Chat] 网络错误，{} 秒后重试第 {} 次...",
                                signal.totalRetries() + 1, signal.totalRetries() + 1)))
                .doOnNext(fullResponse::append)
                .doOnComplete(() -> {
                    // 流式完成后保存AI回复
                    conversationService.saveMessage(conversationId, "assistant", fullResponse.toString());
                    log.info("[Chat] conversationId={} 回复完成，长度={}", conversationId, fullResponse.length());
                })
                .doOnError(e -> log.error("[Chat] conversationId={} 出错", conversationId, e));
    }

    /**
     * 判断是否是网络相关错误（这些错误可以重试）
     */
    private boolean isNetworkError(Throwable throwable) {
        String msg = throwable.getMessage();
        if (msg == null) msg = "";
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
