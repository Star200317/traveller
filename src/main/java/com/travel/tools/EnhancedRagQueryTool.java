package com.travel.tools;

import com.travel.service.EnhancedRagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 增强版RAG查询工具
 *
 * 提供增强的RAG检索功能，支持多种检索模式：
 * 1. enhanced - 完整增强检索（查询重写 + 多查询扩展 + 结果融合）【推荐】
 * 2. contextual - 上下文感知检索（结合对话历史）
 * 3. intent - 意图识别检索（自动识别查询意图）
 * 4. basic - 基础检索（快速简单）
 *
 * 通过 mode 参数选择检索模式，默认为 enhanced。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EnhancedRagQueryTool {

    private final EnhancedRagService enhancedRagService;

    /**
     * 增强检索 - 统一入口
     */
    @Tool(name = "enhanced_rag_query", description = """
        从本地知识库查询旅游信息（景点/酒店/美食/攻略）。

        【最重要】涉及吃喝玩乐/景点/酒店/美食的问题，必须先调用此工具！

        调用时机（必须调用）：
        - 景点相关："有什么景点/好玩的/玩的地方/去哪玩/旅游胜地/打卡点"
        - 美食相关："有什么好吃的/美食/餐厅/特色小吃/推荐吃的"
        - 住宿相关："住哪里/有什么酒店/民宿/客栈/住宿推荐"
        - 综合推荐："有什么推荐/攻略/介绍/吃喝玩乐"

        ⚠️ 绝对红线：
        - ❌ 不查知识库就回答景点/酒店/美食问题
        - ❌ 知识库没查到，也不调用 web_search 搜索
        - ❌ 编造地址、价格、营业时间

        参数：
        - query: 用户的问题（必填），如"丽江有什么好玩的景点"
        - category: 查询分类（可选，提高准确率）
          * attraction = 景点
          * hotel = 酒店住宿
          * food = 美食餐饮
          * city = 城市综合
          * all = 所有类别

        返回：知识库中的景点/酒店/美食信息
        - 如果返回"知识库中暂无相关信息" → 必须立即调用 web_search！

        高级功能（可选参数，普通场景不需要）：
        - mode: 检索模式（默认"enhanced"）
          * enhanced = 增强检索（推荐，查询重写+多查询扩展+结果融合）
          * contextual = 上下文感知（适合追问场景）
          * intent = 意图识别（适合不确定类别时）
          * basic = 基础检索（快速但简单）
        - conversationHistory: 对话历史摘要（可选，提高上下文理解）
        - userPreferences: 用户偏好（可选，提高个性化）
        """)
    public String enhancedQuery(
            @ToolParam(description = "用户的查询问题") String query,
            @ToolParam(description = "查询分类（attraction/hotel/food/city/all）", required = false) String category,
            @ToolParam(description = "检索模式（enhanced/contextual/intent/basic，默认enhanced）", required = false) String mode,
            @ToolParam(description = "对话历史摘要（可选，提高上下文理解）", required = false) String conversationHistory,
            @ToolParam(description = "用户偏好（可选，提高个性化）", required = false) String userPreferences) {

        // 根据 mode 参数决定调用哪个服务方法
        String modeStr = (mode != null && !mode.trim().isEmpty()) ? mode.trim().toLowerCase() : "enhanced";

        try {
            return switch (modeStr) {
                case "contextual" -> {
                    log.info("[EnhancedRagTool] 执行上下文感知检索: query={}", query);
                    String result = enhancedRagService.contextualQuery(
                        query, category, null, 10, conversationHistory, userPreferences);
                    log.info("[EnhancedRagTool] 上下文检索完成，结果长度: {}", result.length());
                    yield result;
                }
                case "intent" -> {
                    log.info("[EnhancedRagTool] 执行意图感知检索: query={}", query);
                    String result = enhancedRagService.intentAwareQuery(query, category, null, 10);
                    log.info("[EnhancedRagTool] 意图感知检索完成，结果长度: {}", result.length());
                    yield result;
                }
                case "basic" -> {
                    log.info("[EnhancedRagTool] 执行基础检索: query={}", query);
                    String result = enhancedRagService.query(query, category, null, 10);
                    log.info("[EnhancedRagTool] 基础检索完成，结果长度: {}", result.length());
                    yield result;
                }
                default -> { // "enhanced" or any other value
                    log.info("[EnhancedRagTool] 执行增强检索: query={}", query);
                    String result = enhancedRagService.enhancedQuery(query, category, null, 10, true);
                    log.info("[EnhancedRagTool] 增强检索完成，结果长度: {}", result.length());
                    yield result;
                }
            };
        } catch (Exception e) {
            log.error("[EnhancedRagQueryTool] 检索失败: query={}, mode={}", query, modeStr, e);
            return "检索失败：" + e.getMessage();
        }
    }
}
