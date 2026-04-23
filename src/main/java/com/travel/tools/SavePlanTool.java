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
            // 支持两种格式：数组 [{day, activities}] 或对象 {days: [...]}
            Map<String, Object> contentMap = new HashMap<>();
            if (planContentJson.trim().startsWith("[")) {
                // 数组格式，包装到 days 字段
                List<?> dayList = objectMapper.readValue(planContentJson, List.class);
                contentMap.put("days", dayList);
            } else {
                // 对象格式
                contentMap = objectMapper.readValue(planContentJson, Map.class);
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
}
