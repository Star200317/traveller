package com.travel.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.service.TravelPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具6：生成地图路线数据
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MapPlanTool {

    private final TravelPlanService travelPlanService;
    private final ObjectMapper objectMapper;

    @Tool(description = "当用户确认旅游计划后，将计划转换为地图路线数据（包含景点经纬度坐标、连线路径、多日分色方案），用于在前端地图上可视化展示。必须在调用save_plan之后使用，planId参数必须是save_plan工具返回的长数字ID")
    public String generateMapPlan(
            @ToolParam(description = "旅游计划JSON数组字符串，格式：[{day:1, activities:[{name, latitude, longitude}]}]") String planJson,
            @ToolParam(description = "关联的计划ID，必须是save_plan工具返回的长数字（如2045786267961270273），不能自己编造") Long planId) {
        try {
            // AI返回的是数组格式，需要解析为List
            List<Map<String, Object>> daysList = objectMapper.readValue(planJson, List.class);
            
            // 转换AI格式为系统内部格式（字段映射）
            List<Map<String, Object>> convertedDays = new ArrayList<>();
            for (Map<String, Object> day : daysList) {
                Map<String, Object> convertedDay = new HashMap<>();
                convertedDay.put("dayIndex", day.getOrDefault("day", 1));
                
                // 转换activities为attractions，并映射经纬度字段
                List<Map<String, Object>> activities = (List<Map<String, Object>>) day.getOrDefault("activities", new ArrayList<>());
                List<Map<String, Object>> attractions = new ArrayList<>();
                for (Map<String, Object> act : activities) {
                    Map<String, Object> attr = new HashMap<>();
                    attr.put("name", act.get("name"));
                    attr.put("address", act.getOrDefault("address", ""));
                    // 经纬度字段映射：latitude -> lat, longitude -> lng
                    Object lat = act.get("latitude");
                    Object lng = act.get("longitude");
                    if (lat != null) attr.put("lat", lat);
                    if (lng != null) attr.put("lng", lng);
                    attr.put("description", act.getOrDefault("description", ""));
                    attractions.add(attr);
                }
                convertedDay.put("attractions", attractions);
                convertedDays.add(convertedDay);
            }
            
            // 转换为Map格式供buildMapData使用
            Map<String, Object> plan = new HashMap<>();
            plan.put("days", convertedDays);
            plan.put("totalDays", convertedDays.size());
            
            Map<String, Object> mapData = travelPlanService.buildMapData(plan);
            travelPlanService.updateMapData(planId, mapData);
            return "地图路线已生成，planId=" + planId + "，共" + mapData.get("totalDays") + "天路线";
        } catch (Exception e) {
            log.error("[MapPlan] 生成失败, planId={}, error={}", planId, e.getMessage(), e);
            return "地图路线生成失败：" + e.getMessage();
        }
    }
}
