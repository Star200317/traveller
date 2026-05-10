package com.travel.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.travel.entity.TravelPlan;
import com.travel.mapper.TravelPlanMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TravelPlanService extends ServiceImpl<TravelPlanMapper, TravelPlan> {

    private final AmapService amapService;

    /**
     * 根据旅游计划JSON构建高德地图所需数据结构
     * planContent 格式：{ "days": [{ "dayIndex":1, "attractions":[...], "hotel":{...} }] }
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> buildMapData(Map<String, Object> planContent) {
        List<Map<String, Object>> days = (List<Map<String, Object>>) planContent.getOrDefault("days", new ArrayList<>());

        // 颜色列表（每天一个颜色）
        String[] dayColors = {"#FF5733", "#33A1FF", "#28C76F", "#FF9F43", "#9B59B6", "#1ABC9C", "#E74C3C"};
        // 酒店颜色（统一使用紫色系）
        String hotelColor = "#8B5CF6";

        List<Map<String, Object>> markers = new ArrayList<>();   // 所有标点
        List<Map<String, Object>> polylines = new ArrayList<>(); // 所有路线
        List<Map<String, Object>> hotels = new ArrayList<>();    // 酒店列表
        List<Map<String, Object>> meals = new ArrayList<>();     // 餐食列表

        for (int d = 0; d < days.size(); d++) {
            Map<String, Object> day = days.get(d);
            List<Map<String, Object>> attractions = (List<Map<String, Object>>) day.getOrDefault("attractions", 
                    day.getOrDefault("activities", new ArrayList<>()));
            String color = dayColors[d % dayColors.length];
            int dayIndex = d + 1;

            List<Map<String, Object>> linePoints = new ArrayList<>();

            // 添加景点标记和路线点
            for (int i = 0; i < attractions.size(); i++) {
                Map<String, Object> attr = attractions.get(i);
                double lat = getDoubleValue(attr, "lat", "latitude");
                double lng = getDoubleValue(attr, "lng", "longitude");
                
                // 如果经纬度为0或不存在，从高德地图获取
                if (lat == 0.0 || lng == 0.0) {
                    String address = (String) attr.get("address");
                    String name = (String) attr.get("name");
                    if (address != null && !address.trim().isEmpty() && !"暂无详细地址".equals(address)) {
                        log.info("[TravelPlan] Day{} 景点 '{}' 经纬度缺失，从高德地图获取: {}", dayIndex, name, address);
                        try {
                            double[] coords = amapService.geocode(address);
                            if (coords != null && coords.length >= 2) {
                                lng = coords[0];
                                lat = coords[1];
                                attr.put("lng", lng);
                                attr.put("lat", lat);
                                log.info("[TravelPlan] Day{} 景点 '{}' 地理编码成功: lng={}, lat={}", dayIndex, name, lng, lat);
                            } else {
                                log.warn("[TravelPlan] Day{} 景点 '{}' 地理编码返回空: {}", dayIndex, name, address);
                            }
                        } catch (Exception e) {
                            log.warn("[TravelPlan] Day{} 景点 '{}' 地理编码异常: {}", dayIndex, name, e.getMessage());
                        }
                    } else {
                        log.warn("[TravelPlan] Day{} 景点 '{}' 缺少有效地址，无法获取经纬度", dayIndex, name);
                    }
                }
                
                log.info("[TravelPlan] Day{} 景点: {}, lat={}, lng={}, rawData={}", dayIndex, attr.get("name"), lat, lng, attr);

                // 验证坐标有效性，无效则跳过该景点
                if (lat == 0.0 || lng == 0.0) {
                    log.warn("[TravelPlan] Day{} 景点 '{}' 经纬度无效(0,0)，跳过: lat={}, lng={}", dayIndex, attr.get("name"), lat, lng);
                    continue;
                }

                // 景点标记点
                Map<String, Object> marker = new HashMap<>();
                marker.put("name", attr.get("name"));
                marker.put("address", attr.getOrDefault("address", ""));
                marker.put("lat", lat);
                marker.put("lng", lng);
                marker.put("day", dayIndex);
                marker.put("order", i + 1);
                marker.put("color", color);
                marker.put("type", "attraction");  // 标记类型：景点
                marker.put("description", attr.getOrDefault("description", ""));
                marker.put("time", attr.getOrDefault("time", ""));
                marker.put("ticket", attr.getOrDefault("ticket", ""));
                markers.add(marker);

                // 路线点
                Map<String, Object> point = new HashMap<>();
                point.put("lat", lat);
                point.put("lng", lng);
                point.put("name", attr.get("name"));
                linePoints.add(point);
            }

            // 添加餐食标记
            List<Map<String, Object>> dayMeals = (List<Map<String, Object>>) day.get("meals");
            if (dayMeals != null) {
                for (Map<String, Object> meal : dayMeals) {
                    double mealLat = getDoubleValue(meal, "lat", "latitude");
                    double mealLng = getDoubleValue(meal, "lng", "longitude");
                    
                    // 如果餐食经纬度为0或不存在，从高德地图获取
                    if (mealLat == 0.0 || mealLng == 0.0) {
                        String mealAddress = (String) meal.get("address");
                        String mealName = (String) meal.get("name");
                        if (mealAddress != null && !mealAddress.trim().isEmpty() && !"暂无详细地址".equals(mealAddress)) {
                            log.info("[TravelPlan] Day{} 餐食 '{}' 经纬度缺失，从高德地图获取: {}", dayIndex, mealName, mealAddress);
                            try {
                                double[] coords = amapService.geocode(mealAddress);
                                if (coords != null && coords.length >= 2) {
                                    mealLng = coords[0];
                                    mealLat = coords[1];
                                    meal.put("lng", mealLng);
                                    meal.put("lat", mealLat);
                                    log.info("[TravelPlan] Day{} 餐食 '{}' 地理编码成功: lng={}, lat={}", dayIndex, mealName, mealLng, mealLat);
                                } else {
                                    log.warn("[TravelPlan] Day{} 餐食 '{}' 地理编码返回空: {}", dayIndex, mealName, mealAddress);
                                }
                            } catch (Exception e) {
                                log.warn("[TravelPlan] Day{} 餐食 '{}' 地理编码异常: {}", dayIndex, mealName, e.getMessage());
                            }
                        }
                    }
                    
                    String mealType = (String) meal.get("type");
                    String mealIcon = "早餐".equals(mealType) ? "🍳" : ("午餐".equals(mealType) ? "🍱" : "🍽️");
                    
                    Map<String, Object> mealMarker = new HashMap<>();
                    mealMarker.put("name", mealIcon + " " + meal.get("name"));
                    mealMarker.put("address", meal.getOrDefault("address", ""));
                    mealMarker.put("lat", mealLat);
                    mealMarker.put("lng", mealLng);
                    mealMarker.put("day", dayIndex);
                    mealMarker.put("type", "meal");
                    mealMarker.put("mealType", mealType);
                    mealMarker.put("time", meal.get("time"));
                    mealMarker.put("price", meal.get("price"));
                    mealMarker.put("recommendation", meal.get("recommendation"));
                    mealMarker.put("description", mealType + "：" + meal.get("recommendation"));
                    markers.add(mealMarker);
                    meals.add(mealMarker);
                }
            }

            // 添加酒店标记
            Map<String, Object> hotel = (Map<String, Object>) day.get("hotel");
            if (hotel != null) {
                double hotelLat = getDoubleValue(hotel, "lat", "latitude");
                double hotelLng = getDoubleValue(hotel, "lng", "longitude");
                
                // 如果酒店经纬度为0或不存在，从高德地图获取
                if (hotelLat == 0.0 || hotelLng == 0.0) {
                    String hotelAddress = (String) hotel.get("address");
                    String hotelName = (String) hotel.get("name");
                    if (hotelAddress != null && !hotelAddress.trim().isEmpty() && !"暂无详细地址".equals(hotelAddress)) {
                        log.info("[TravelPlan] Day{} 酒店 '{}' 经纬度缺失，从高德地图获取: {}", dayIndex, hotelName, hotelAddress);
                        try {
                            double[] coords = amapService.geocode(hotelAddress);
                            if (coords != null && coords.length >= 2) {
                                hotelLng = coords[0];
                                hotelLat = coords[1];
                                hotel.put("lng", hotelLng);
                                hotel.put("lat", hotelLat);
                                log.info("[TravelPlan] Day{} 酒店 '{}' 地理编码成功: lng={}, lat={}", dayIndex, hotelName, hotelLng, hotelLat);
                            } else {
                                log.warn("[TravelPlan] Day{} 酒店 '{}' 地理编码返回空: {}", dayIndex, hotelName, hotelAddress);
                            }
                        } catch (Exception e) {
                            log.warn("[TravelPlan] Day{} 酒店 '{}' 地理编码异常: {}", dayIndex, hotelName, e.getMessage());
                        }
                    } else {
                        log.warn("[TravelPlan] Day{} 酒店 '{}' 缺少有效地址，无法获取经纬度", dayIndex, hotelName);
                    }
                }
                
                // 只有酒店坐标有效时才添加到标记和路线
                if (hotelLat != 0.0 && hotelLng != 0.0) {
                    Map<String, Object> hotelMarker = new HashMap<>();
                    hotelMarker.put("name", hotel.get("name"));
                    hotelMarker.put("address", hotel.getOrDefault("address", ""));
                    hotelMarker.put("lat", hotelLat);
                    hotelMarker.put("lng", hotelLng);
                    hotelMarker.put("day", dayIndex);
                    hotelMarker.put("order", 0);  // 酒店排在最前
                    hotelMarker.put("color", hotelColor);
                    hotelMarker.put("type", "hotel");  // 标记类型：酒店
                    hotelMarker.put("description", "价格：" + hotel.getOrDefault("price", ""));
                    hotelMarker.put("price", hotel.getOrDefault("price", ""));
                    markers.add(hotelMarker);
                    hotels.add(hotelMarker);
                    
                    // 将酒店添加到路线点（作为起点）
                    Map<String, Object> hotelPoint = new HashMap<>();
                    hotelPoint.put("lat", hotelLat);
                    hotelPoint.put("lng", hotelLng);
                    hotelPoint.put("name", hotel.get("name"));
                    linePoints.add(0, hotelPoint);  // 插入到最前面
                    
                    // 酒店到第一个景点的路线（使用当天颜色）
                    if (linePoints.size() >= 2) {
                        Map<String, Object> start = linePoints.get(0);
                        Map<String, Object> end = linePoints.get(1);
                        addRouteSegment(start, end, color, dayIndex, polylines, true);
                    }
                } else {
                    log.warn("[TravelPlan] Day{} 酒店 '{}' 经纬度无效(0,0)，跳过: lat={}, lng={}", dayIndex, hotel.get("name"), hotelLat, hotelLng);
                }
            }

            // 当天景点之间的路线 - 使用高德路线规划API获取真实路径（使用当天颜色）
            if (linePoints.size() > 1) {
                // 已经有酒店到第一个景点的路线，现在添加景点之间的路线
                for (int i = 1; i < linePoints.size() - 1; i++) {
                    Map<String, Object> start = linePoints.get(i);
                    Map<String, Object> end = linePoints.get(i + 1);
                    addRouteSegment(start, end, color, dayIndex, polylines, false);
                }
            }
        }

        // 计算地图中心点（所有标点的平均经纬度），如果markers为空则用默认中心（北京）
        double centerLat = 39.9;
        double centerLng = 116.4;
        if (!markers.isEmpty()) {
            centerLat = markers.stream().mapToDouble(m -> {
                Object v = m.get("lat");
                return v instanceof Number ? ((Number) v).doubleValue() : 39.9;
            }).average().orElse(39.9);
            centerLng = markers.stream().mapToDouble(m -> {
                Object v = m.get("lng");
                return v instanceof Number ? ((Number) v).doubleValue() : 116.4;
            }).average().orElse(116.4);
        }

        Map<String, Object> mapData = new HashMap<>();
        mapData.put("center", Map.of("lat", centerLat, "lng", centerLng));
        mapData.put("zoom", 12);
        mapData.put("markers", markers);   // 修复：markers → markers
        mapData.put("polylines", polylines);
        mapData.put("hotels", hotels);
        mapData.put("meals", meals);
        mapData.put("totalDays", days.size());
        log.info("[buildMapData] 完成：{}个标记点，{}条路线，{}个酒店", markers.size(), polylines.size(), hotels.size());
        return mapData;
    }
    
    /**
     * 添加路线段
     */
    private void addRouteSegment(Map<String, Object> start, Map<String, Object> end, 
                                  String color, int dayIndex, 
                                  List<Map<String, Object>> polylines, boolean isHotelRoute) {
        double startLng = getDoubleValue(start, "lng", "longitude");
        double startLat = getDoubleValue(start, "lat", "latitude");
        double endLng = getDoubleValue(end, "lng", "longitude");
        double endLat = getDoubleValue(end, "lat", "latitude");

        // 验证坐标有效性，无效则跳过该路段
        if (startLat == 0.0 || startLng == 0.0 || endLat == 0.0 || endLng == 0.0) {
            log.warn("[TravelPlan] Day{} {}路线坐标无效，跳过: start={}({},{}), end={}({},{})",
                dayIndex, isHotelRoute ? "酒店" : "",
                start.get("name"), startLat, startLng,
                end.get("name"), endLat, endLng);
            return;
        }

        // 调用高德API规划路线（异常捕获，避免单段路线失败导致整体崩溃）
        Map<String, Object> routeResult = null;
        try {
            routeResult = amapService.planDrivingRoute(startLng, startLat, endLng, endLat);
        } catch (Exception e) {
            log.warn("[TravelPlan] Day{} {}路线规划异常: {}→{}，将使用直线。原因: {}",
                dayIndex, isHotelRoute ? "酒店" : "", start.get("name"), end.get("name"), e.getMessage());
        }

        List<Map<String, Object>> routePoints = new ArrayList<>();
        
        // 添加起点
        routePoints.add(Map.of(
            "lat", startLat,
            "lng", startLng,
            "name", start.getOrDefault("name", "起点")
        ));

        if (routeResult != null && routeResult.containsKey("path")) {
            @SuppressWarnings("unchecked")
            List<double[]> path = (List<double[]>) routeResult.get("path");
            int distance = (int) routeResult.getOrDefault("distance", 0);
            int duration = (int) routeResult.getOrDefault("duration", 0);

            log.info("[TravelPlan] Day{} {}路线 {}->{}: {}米, {}秒, {}个点",
                dayIndex, isHotelRoute ? "酒店" : "", start.get("name"), end.get("name"), distance, duration, path.size());

            // 添加路径点（跳过第一个点，因为已经在上一个segment的终点了）
            for (int j = 1; j < path.size(); j++) {
                double[] coord = path.get(j);
                routePoints.add(Map.of(
                    "lat", coord[1],
                    "lng", coord[0]
                ));
            }
        } else {
            // API调用失败，使用直线
            log.warn("[TravelPlan] Day{} {}路线规划失败，使用直线: {}->{}",
                dayIndex, isHotelRoute ? "酒店" : "", start.get("name"), end.get("name"));
        }
        
        // 添加终点
        routePoints.add(Map.of(
            "lat", endLat,
            "lng", endLng,
            "name", end.getOrDefault("name", "终点")
        ));

        Map<String, Object> polyline = new HashMap<>();
        polyline.put("day", dayIndex);
        polyline.put("color", color);
        polyline.put("points", routePoints);
        polyline.put("isHotelRoute", isHotelRoute);
        polylines.add(polyline);
    }

    /**
     * 构建真实路线（使用高德路线规划API）
     */
    private List<Map<String, Object>> buildRealRoute(List<Map<String, Object>> points, String color, int dayIndex) {
        List<Map<String, Object>> routePoints = new ArrayList<>();

        // 添加起点
        Map<String, Object> firstPoint = points.get(0);
        routePoints.add(Map.of(
            "lat", firstPoint.get("lat"),
            "lng", firstPoint.get("lng"),
            "name", firstPoint.getOrDefault("name", "起点")
        ));

        // 对每对相邻点规划路线
        for (int i = 0; i < points.size() - 1; i++) {
            Map<String, Object> start = points.get(i);
            Map<String, Object> end = points.get(i + 1);

            double startLng = getDoubleValue(start, "lng");
            double startLat = getDoubleValue(start, "lat");
            double endLng = getDoubleValue(end, "lng");
            double endLat = getDoubleValue(end, "lat");

            // 验证坐标有效性
            if (startLat == 0.0 || startLng == 0.0 || endLat == 0.0 || endLng == 0.0) {
                log.warn("[TravelPlan] Day{} 路线段坐标无效，跳过: start={}({},{}), end={}({},{})",
                    dayIndex, start.get("name"), startLat, startLng, end.get("name"), endLat, endLng);
                continue;
            }

            // 调用高德API规划路线（异常捕获）
            Map<String, Object> routeResult = null;
            try {
                routeResult = amapService.planDrivingRoute(startLng, startLat, endLng, endLat);
            } catch (Exception e) {
                log.warn("[TravelPlan] Day{} 路线段规划异常: {}→{}，将使用直线。原因: {}",
                    dayIndex, start.get("name"), end.get("name"), e.getMessage());
            }

            if (routeResult != null && routeResult.containsKey("path")) {
                @SuppressWarnings("unchecked")
                List<double[]> path = (List<double[]>) routeResult.get("path");
                int distance = (int) routeResult.getOrDefault("distance", 0);
                int duration = (int) routeResult.getOrDefault("duration", 0);

                log.info("[TravelPlan] Day{} 路线段 {}->{}: {}米, {}秒, {}个点",
                    dayIndex, start.get("name"), end.get("name"), distance, duration, path.size());

                // 添加路径点（跳过第一个点，因为已经在上一个segment的终点了）
                for (int j = 1; j < path.size(); j++) {
                    double[] coord = path.get(j);
                    routePoints.add(Map.of(
                        "lat", coord[1],
                        "lng", coord[0]
                    ));
                }

                // 如果是最后一段，添加终点信息
                if (i == points.size() - 2) {
                    routePoints.add(Map.of(
                        "lat", end.get("lat"),
                        "lng", end.get("lng"),
                        "name", end.getOrDefault("name", "终点")
                    ));
                }
            } else {
                // API调用失败，使用直线
                log.warn("[TravelPlan] Day{} 路线规划失败，使用直线: {}->{}",
                    dayIndex, start.get("name"), end.get("name"));
                routePoints.add(Map.of(
                    "lat", end.get("lat"),
                    "lng", end.get("lng"),
                    "name", end.getOrDefault("name", "终点")
                ));
            }
        }

        return routePoints;
    }

    private double getDoubleValue(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object val = map.get(key);
            if (val instanceof Number) {
                return ((Number) val).doubleValue();
            }
            if (val instanceof String) {
                try {
                    return Double.parseDouble((String) val);
                } catch (NumberFormatException e) {
                    continue;
                }
            }
        }
        return 0.0;
    }

    /**
     * 更新计划的地图数据
     * 使用 UpdateWrapper 裸写 UPDATE，完全绕过 MyBatis-Plus 拦截，
     * 确保 map_data 字段一定能被写入数据库
     */
    public void updateMapData(Long planId, Map<String, Object> mapData) {
        // 先序列化 mapData 为 JSON 字符串，避免 JacksonTypeHandler 序列化问题
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String mapDataJson = mapper.writeValueAsString(mapData);

            UpdateWrapper<TravelPlan> uw = new UpdateWrapper<>();
            uw.eq("id", planId);
            uw.set("map_data", mapDataJson);  // 直接用 JSON 字符串更新
            uw.set("status", 2);               // 已确认
            int rows = baseMapper.update(null, uw);
            log.info("[TravelPlan] 更新mapData: planId={}, 影响行数={}, dataLength={}", planId, rows, mapDataJson.length());
        } catch (Exception e) {
            log.error("[TravelPlan] updateMapData 失败, planId={}, error={}", planId, e.getMessage(), e);
            throw new RuntimeException("保存地图数据失败: " + e.getMessage(), e);
        }
    }

    /**
     * 逻辑删除计划
     * 使用 UpdateWrapper 裸写 UPDATE，完全绕过 MyBatis-Plus 逻辑删除拦截，
     * 确保 deleted 字段一定被更新为 1
     */
    public boolean deletePlan(Long planId) {
        UpdateWrapper<TravelPlan> uw = new UpdateWrapper<>();
        uw.eq("id", planId);
        uw.set("deleted", 1);
        int rows = baseMapper.update(null, uw);
        log.info("[TravelPlan] 逻辑删除 planId={}, 影响行数={}", planId, rows);
        return rows > 0;
    }
}
