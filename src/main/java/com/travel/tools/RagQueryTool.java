package com.travel.tools;

import com.travel.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工具5：RAG知识库查询
 */
@Component
@RequiredArgsConstructor
public class RagQueryTool {

    private final RagService ragService;

    @Tool(description = "查询本地旅游知识库，获取景点介绍、城市信息、旅行攻略、注意事项等专业知识")
    public String ragQuery(
            @ToolParam(description = "查询内容，如'西安必去景点推荐'") String query,
            @ToolParam(description = "查询分类：attraction（景点）/city（城市）/tip（旅行常识），不确定填all") String category) {
        return ragService.query(query, category, null, 5);
    }
}
