package com.travel.tools;

import com.travel.entity.TravelPlan;
import com.travel.service.AmapService;
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
    private final AmapService amapService;

    @Tool(description = "生成计划的同时生成地图路线数据（包含景点经纬度坐标、酒店位置、连线路径、多日分色方案），用于在前端地图上可视化展示。必须在调用save_plan之后使用，planId参数必须是save_plan工具刚刚返回的长数字ID。⚠️ 只能对当前新保存的计划调用此工具，绝对不能使用旧的planId！生成后向用户展示地图链接 http://localhost:5173/map/{planId}")
    public String generateMapPlan(
            @ToolParam(description = "关联的计划ID【必须使用save_plan刚刚返回的新ID】，如2053330681269071874。绝对禁止使用旧planId或自己编造的数字") Long planId) {
        try {
            // 根据 planId 从数据库读取已保存的计划内容
            TravelPlan savedPlan = travelPlanService.getById(planId);
            if (savedPlan == null) {
                return "❌ 地图路线生成失败：计划ID不存在或已被删除。请确认你使用的planId是最近一次save_plan返回的ID。";
            }

            // 安全检查：如果该计划已有有效mapData且有标记点，说明已经生成过地图了
            Map<String, Object> existingMapData = savedPlan.getMapData();
            if (existingMapData != null) {
                Object markers = existingMapData.get("markers");
                if (markers instanceof List && !((List<?>) markers).isEmpty()) {
                    log.info("[MapPlan] planId={} 已有mapData(markers={}),跳过重复生成", 
                            planId, ((List<?>) markers).size());
                    return "✅ 该计划的地图数据已存在（" + ((List<?>) markers).size() + "个标记点），无需重新生成。\n" +
                           "直接向用户展示地图链接即可：🗺️ [点击查看完整地图](http://localhost:5173/map/" + planId + ")";
                }
            }

            Map<String, Object> planContent = savedPlan.getPlanContent();
            if (planContent == null || planContent.isEmpty()) {
                return "地图路线生成失败：计划内容为空，请先调用save_plan保存计划";
            }

            // 转换数据库格式为系统内部格式，并补全缺失的地址信息
            List<Map<String, Object>> convertedDays = convertAndEnrichPlan(planContent);

            // 转换为Map格式供buildMapData使用
            Map<String, Object> plan = new HashMap<>();
            plan.put("days", convertedDays);
            plan.put("totalDays", convertedDays.size());

            Map<String, Object> mapData = travelPlanService.buildMapData(plan);
            @SuppressWarnings("unchecked")
            List<?> markerList = (List<?>) mapData.getOrDefault("markers", new ArrayList<>());
            @SuppressWarnings("unchecked")
            List<?> polylineList = (List<?>) mapData.getOrDefault("polylines", new ArrayList<>());
            log.info("[MapPlan] 生成的mapData: markers={}, polylines={}",
                markerList.size(), polylineList.size());

            travelPlanService.updateMapData(planId, mapData);
            log.info("[MapPlan] mapData已保存到数据库, planId={}", planId);

            // 补全后的地址回写到 planContent（让 PDF 导出等场景也能用上）
            try {
                TravelPlan planEntity = travelPlanService.getById(planId);
                if (planEntity != null && planEntity.getPlanContent() != null) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> savedDays = (List<Map<String, Object>>) planEntity.getPlanContent().get("days");
                    if (savedDays != null) {
                        // 将 convertedDays（已补全地址）同步回 planContent
                        savedDays.clear();
                        savedDays.addAll(convertedDays);
                        travelPlanService.updateById(planEntity);
                        log.info("[MapPlan] 补全地址已回写到 planContent, planId={}", planId);
                    }
                }
            } catch (Exception e) {
                log.warn("[MapPlan] 回写 planContent 失败, planId={}, error={}", planId, e.getMessage());
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> hotels = (List<Map<String, Object>>) mapData.getOrDefault("hotels", new ArrayList<>());
            return "地图路线已生成，planId=" + planId + "，共" + mapData.get("totalDays") + "天路线，包含" + hotels.size() + "个酒店标记";
        } catch (Exception e) {
            log.error("[MapPlan] 生成失败, planId={}, error={}", planId, e.getMessage(), e);
            return "地图路线生成失败：" + e.getMessage();
        }
    }

    /**
     * 将数据库存储的 planContent 转换为内部格式，并补全缺失的地址信息
     * 主要处理字段映射：activities → attractions，latitude → lat
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> convertAndEnrichPlan(Map<String, Object> planContent) {
        List<Map<String, Object>> convertedDays = new ArrayList<>();
        List<Map<String, Object>> days = (List<Map<String, Object>>) planContent.getOrDefault("days", new ArrayList<>());

        for (Map<String, Object> day : days) {
            Map<String, Object> convertedDay = new HashMap<>();
            convertedDay.put("dayIndex", day.getOrDefault("day", day.getOrDefault("dayIndex", 1)));

            // 转换景点信息，并补全缺失地址
            List<Map<String, Object>> activities = (List<Map<String, Object>>) day.getOrDefault("activities", new ArrayList<>());
            List<Map<String, Object>> attractions = new ArrayList<>();
            for (Map<String, Object> act : activities) {
                Map<String, Object> attr = new HashMap<>();
                attr.put("name", act.get("name"));
                attr.put("time", stringVal(act, "time"));
                attr.put("ticket", stringVal(act, "ticket"));
                attr.put("description", stringVal(act, "description"));

                // 地址处理：有坐标无地址 → 调用高德逆地理编码补全
                String address = stringVal(act, "address");
                Object lat = act.get("latitude");
                Object lng = act.get("longitude");

                if (address.trim().isEmpty() && lat != null && lng != null) {
                    double latVal = toDouble(lat);
                    double lngVal = toDouble(lng);
                    if (latVal != 0 || lngVal != 0) {
                        try {
                            String geocoded = amapService.regeocode(lngVal, latVal);
                            if (geocoded != null && !geocoded.trim().isEmpty()) {
                                address = geocoded;
                                log.info("[MapPlan] 景点[{}]逆地理编码补全地址: {}", act.get("name"), address);
                            }
                        } catch (Exception e) {
                            log.warn("[MapPlan] 景点[{}]逆地理编码异常: {}", act.get("name"), e.getMessage());
                        }
                    }
                }
                attr.put("address", address.isEmpty() ? "暂无详细地址" : address);

                // 经纬度字段映射：latitude -> lat, longitude -> lng
                if (lat != null) attr.put("lat", lat);
                if (lng != null) attr.put("lng", lng);

                attractions.add(attr);
            }
            convertedDay.put("attractions", attractions);

            // 处理酒店信息，并补全缺失地址
            Map<String, Object> hotel = (Map<String, Object>) day.get("hotel");
            if (hotel != null) {
                Map<String, Object> convertedHotel = new HashMap<>();
                convertedHotel.put("name", hotel.get("name"));
                convertedHotel.put("price", stringVal(hotel, "price"));

                String hotelAddr = stringVal(hotel, "address");
                Object hotelLat = hotel.get("latitude");
                Object hotelLng = hotel.get("longitude");

                if (hotelAddr.trim().isEmpty() && hotelLat != null && hotelLng != null) {
                    double latVal = toDouble(hotelLat);
                    double lngVal = toDouble(hotelLng);
                    if (latVal != 0 || lngVal != 0) {
                        try {
                            String geocoded = amapService.regeocode(lngVal, latVal);
                            if (geocoded != null && !geocoded.trim().isEmpty()) {
                                hotelAddr = geocoded;
                                log.info("[MapPlan] 酒店[{}]逆地理编码补全地址: {}", hotel.get("name"), hotelAddr);
                            }
                        } catch (Exception e) {
                            log.warn("[MapPlan] 酒店[{}]逆地理编码异常: {}", hotel.get("name"), e.getMessage());
                        }
                    }
                }
                convertedHotel.put("address", hotelAddr.isEmpty() ? "暂无详细地址" : hotelAddr);

                if (hotelLat != null) convertedHotel.put("lat", hotelLat);
                if (hotelLng != null) convertedHotel.put("lng", hotelLng);
                convertedDay.put("hotel", convertedHotel);
            }

            // 处理餐食信息
            List<Map<String, Object>> meals = (List<Map<String, Object>>) day.get("meals");
            if (meals != null) {
                List<Map<String, Object>> convertedMeals = new ArrayList<>();
                for (Map<String, Object> meal : meals) {
                    Map<String, Object> convertedMeal = new HashMap<>();
                    convertedMeal.put("name", meal.get("name"));
                    convertedMeal.put("type", meal.get("type"));
                    convertedMeal.put("time", meal.get("time"));
                    convertedMeal.put("address", stringVal(meal, "address"));
                    convertedMeal.put("price", stringVal(meal, "price"));
                    convertedMeal.put("recommendation", stringVal(meal, "recommendation"));
                    Object mealLat = meal.get("latitude");
                    Object mealLng = meal.get("longitude");
                    if (mealLat != null) convertedMeal.put("lat", mealLat);
                    if (mealLng != null) convertedMeal.put("lng", mealLng);
                    convertedMeals.add(convertedMeal);
                }
                convertedDay.put("meals", convertedMeals);
            }

            convertedDays.add(convertedDay);
        }

        return convertedDays;
    }

    /**
     * 安全获取字符串值
     */
    private String stringVal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return "";
        return val.toString();
    }

    /**
     * 将 Object 类型安全转换为 double
     */
    private double toDouble(Object val) {
        if (val == null) return 0.0;
        if (val instanceof Number) return ((Number) val).doubleValue();
        try {
            return Double.parseDouble(val.toString().trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
