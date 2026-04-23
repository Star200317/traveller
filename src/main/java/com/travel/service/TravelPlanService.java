package com.travel.service;

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
     * planContent 格式：{ "days": [{ "dayIndex":1, "attractions":[{"name":"故宫","address":"北京市东城区","lat":39.916,"lng":116.397},...] }] }
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> buildMapData(Map<String, Object> planContent) {
        List<Map<String, Object>> days = (List<Map<String, Object>>) planContent.getOrDefault("days", new ArrayList<>());

        // 颜色列表（每天一个颜色）
        String[] dayColors = {"#FF5733", "#33A1FF", "#28C76F", "#FF9F43", "#9B59B6", "#1ABC9C", "#E74C3C"};

        List<Map<String, Object>> markers = new ArrayList<>();   // 所有标点
        List<Map<String, Object>> polylines = new ArrayList<>(); // 所有路线

        for (int d = 0; d < days.size(); d++) {
            Map<String, Object> day = days.get(d);
            List<Map<String, Object>> attractions = (List<Map<String, Object>>) day.getOrDefault("attractions", new ArrayList<>());
            String color = dayColors[d % dayColors.length];
            int dayIndex = d + 1;

            List<Map<String, Object>> linePoints = new ArrayList<>();

            for (int i = 0; i < attractions.size(); i++) {
                Map<String, Object> attr = attractions.get(i);
                double lat = getDoubleValue(attr, "lat");
                double lng = getDoubleValue(attr, "lng");

                // 标记点
                Map<String, Object> marker = new HashMap<>();
                marker.put("name", attr.get("name"));
                marker.put("address", attr.getOrDefault("address", ""));
                marker.put("lat", lat);
                marker.put("lng", lng);
                marker.put("day", dayIndex);
                marker.put("order", i + 1);
                marker.put("color", color);
                marker.put("description", attr.getOrDefault("description", ""));
                markers.add(marker);

                // 路线点
                Map<String, Object> point = new HashMap<>();
                point.put("lat", lat);
                point.put("lng", lng);
                point.put("name", attr.get("name"));
                linePoints.add(point);
            }

            // 当天路线 - 使用高德路线规划API获取真实路径
            if (linePoints.size() > 1) {
                List<Map<String, Object>> routePoints = buildRealRoute(linePoints, color, dayIndex);

                Map<String, Object> polyline = new HashMap<>();
                polyline.put("day", dayIndex);
                polyline.put("color", color);
                polyline.put("points", routePoints);
                polylines.add(polyline);
            }
        }

        // 计算地图中心点（所有标点的平均经纬度）
        double centerLat = markers.stream().mapToDouble(m -> (Double) m.get("lat")).average().orElse(39.9);
        double centerLng = markers.stream().mapToDouble(m -> (Double) m.get("lng")).average().orElse(116.4);

        Map<String, Object> mapData = new HashMap<>();
        mapData.put("center", Map.of("lat", centerLat, "lng", centerLng));
        mapData.put("zoom", 12);
        mapData.put("markers", markers);
        mapData.put("polylines", polylines);
        mapData.put("totalDays", days.size());
        return mapData;
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

            // 调用高德API规划路线
            Map<String, Object> routeResult = amapService.planDrivingRoute(startLng, startLat, endLng, endLat);

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

    private double getDoubleValue(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        }
        if (val instanceof String) {
            return Double.parseDouble((String) val);
        }
        return 0.0;
    }

    public void updateMapData(Long planId, Map<String, Object> mapData) {
        TravelPlan plan = new TravelPlan();
        plan.setId(planId);
        plan.setMapData(mapData);
        plan.setStatus(2);  // 已确认
        updateById(plan);
    }
}
