package com.travel.tools;

import com.travel.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工具5：RAG知识库查询
 * 所有酒店/景点/美食信息必须优先通过此工具获取，禁止AI自行编造
 */
@Component
@RequiredArgsConstructor
public class RagQueryTool {

    private final RagService ragService;

    @Tool(description = """
            【强制工具】查询本地旅游知识库，获取真实可靠的酒店/景点/美食数据！
            
            🔴🔴🔴 何时必须调用此工具（不可跳过！）：
            - 用户询问任何酒店信息 → 立即调用：ragQuery("城市名", "hotel")
            - 用户询问任何景点信息 → 立即调用：ragQuery("城市名", "attraction")
            - 用户询问任何美食信息 → 立即调用：ragQuery("城市名", "food")
            - 用户询问城市攻略 → 立即调用：ragQuery("城市名", "city")
            - 不确定分类 → 调用：ragQuery("城市名", "all")
            
            ✅✅✅ 工具返回内容说明：
            - 返回知识库中的真实数据（酒店名称、地址、价格、介绍等）
            - 【地址精度要求】返回的地址【必须】精确到门牌号级别！
              正确示例："三亚市吉阳区海棠北路36号"（有门牌号36号）
              错误示例："三亚市海棠区蜈支洲岛"（只有区域，无门牌号）→ 此类地址不允许直接给用户！
            - 如果知识库中的地址不精确到门牌号：
              → 工具返回结果中会标注 "⚠️地址未精确到门牌号"
              → AI看到此标注后，【必须】再调用 web_search 搜索该地点的精确门牌号地址
              → 在最终输出前，【绝对禁止】把不精确的地址呈现给用户！
            - 返回"知识库中暂无相关信息"时，才可以调用 web_search 补充
            
            ❌❌❌ 禁止行为：
            - 不调用此工具就回答酒店/景点/美食问题
            - 用你自己的知识列举酒店/景点/美食名称
            - 编造任何地址、价格、介绍信息
            - 【最高优先级】将不精确到门牌号的地址呈现给用户！
            """)
    public String ragQuery(
            @ToolParam(description = "查询关键词，必须是城市名+类型，如'三亚 酒店'、'厦门 美食'、'大理 景点'") String query,
            @ToolParam(description = "分类：hotel=酒店 / attraction=景点 / food=美食 / city=城市攻略 / all=全部，必须明确指定不要留空！") String category) {
        // topK=10，返回更多候选结果，供AI筛选
        return ragService.query(query, category, null, 10);
    }
}
