package com.travel.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.entity.TravelPlan;
import com.travel.service.TravelPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具：保存旅游计划到数据库
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SavePlanTool {

    private final TravelPlanService travelPlanService;
    private final ObjectMapper objectMapper;

    @Tool(description = "当用户确认旅游计划后，将计划保存到数据库。返回值为计划ID（长数字字符串，如'2045786267961270273'），必须原样用于后续的map_plan和pdf_export工具调用")
    public String savePlan(
            @ToolParam(description = "当前用户ID") Long userId,
            @ToolParam(description = "当前会话ID") Long conversationId,
            @ToolParam(description = "计划标题，如'西安5日游'") String title,
            @ToolParam(description = "目的地城市") String destination,
            @ToolParam(description = "出行天数") Integer days,
            @ToolParam(description = "出行人数，默认1") Integer peopleCount,
            @ToolParam(description = "预算金额（元），可选") Double budget,
            @ToolParam(description = "计划详情JSON字符串，包含每日行程安排") String planContentJson) {
        try {
            TravelPlan plan = new TravelPlan();
            plan.setUserId(userId);
            plan.setConversationId(conversationId);
            plan.setTitle(title);
            plan.setDestination(destination);
            plan.setDays(days);
            plan.setPeopleCount(peopleCount != null ? peopleCount : 1);
            plan.setBudget(budget != null ? BigDecimal.valueOf(budget) : null);
            
            // 清洗JSON字符串：移除非法转义字符
            String cleanedJson = cleanJsonString(planContentJson);
            
            // 支持两种格式：数组 [{day, activities}] 或对象 {days: [...]}
            Map<String, Object> contentMap = new HashMap<>();
            if (cleanedJson.trim().startsWith("[")) {
                // 数组格式，包装到 days 字段
                List<?> dayList = objectMapper.readValue(cleanedJson, List.class);
                contentMap.put("days", dayList);
            } else {
                // 对象格式
                contentMap = objectMapper.readValue(cleanedJson, Map.class);
            }
            plan.setPlanContent(contentMap);
            plan.setStatus(1); // 草稿状态
            
            travelPlanService.save(plan);
            
            log.info("[SavePlan] 计划已保存: planId={}, title={}", plan.getId(), title);
            return String.valueOf(plan.getId());
        } catch (Exception e) {
            log.error("[SavePlan] 保存失败", e);
            return "保存失败：" + e.getMessage();
        }
    }
    
    /**
     * 清洗JSON字符串，移除非法转义字符
     */
    private String cleanJsonString(String json) {
        if (json == null) {
            return null;
        }
        // 1. 移除反斜杠后跟空格的非法转义: \ 
        json = json.replace("\\ ", " ");
        // 2. 移除反斜杠后跟换行的非法转义
        json = json.replace("\\\n", " ");
        json = json.replace("\\\r", " ");
        // 3. 移除反斜杠在字符串末尾的情况（如 "...\ " 或 "...\\"）
        json = json.replaceAll("(\"[^\"]*)\\\\\\s*\"", "$1\"");
        // 4. 移除单独的反斜杠（不在有效转义序列中的）
        json = json.replaceAll("(?<!\\\\)\\\\(?!\"|/|b|f|n|r|t|u[0-9a-fA-F]{4})", "");
        // 5. 替换多个连续反斜杠为单个
        json = json.replaceAll("\\\\{2,}", "\\\\");
        return json;
    }
}
