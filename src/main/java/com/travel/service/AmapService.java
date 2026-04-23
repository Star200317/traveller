package com.travel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 高德地图服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AmapService {

    @Value("${amap.web-key}")
    private String webKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper;

    private static final String DIRECTION_API = "https://restapi.amap.com/v3/direction/driving";

    /**
     * 规划驾驶路线
     *
     * @param originLng 起点经度
     * @param originLat 起点纬度
     * @param destLng   终点经度
     * @param destLat   终点纬度
     * @return 路线信息 {path: [[lng,lat],...], distance: 米, duration: 秒}
     */
    public Map<String, Object> planDrivingRoute(double originLng, double originLat, double destLng, double destLat) {
        try {
            String origin = String.format("%.6f,%.6f", originLng, originLat);
            String destination = String.format("%.6f,%.6f", destLng, destLat);

            String url = UriComponentsBuilder.fromHttpUrl(DIRECTION_API)
                    .queryParam("key", webKey)
                    .queryParam("origin", origin)
                    .queryParam("destination", destination)
                    .queryParam("extensions", "base")
                    .queryParam("output", "json")
                    .toUriString();

            log.info("[Amap] 请求路线规划: {} -> {}", origin, destination);

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());

            if (!"1".equals(root.path("status").asText())) {
                String info = root.path("info").asText();
                log.error("[Amap] 路线规划失败: {}", info);
                return null;
            }

            JsonNode route = root.path("route").path("paths").get(0);
            if (route == null) {
                log.error("[Amap] 路线数据为空");
                return null;
            }

            // 提取距离和时长
            int distance = route.path("distance").asInt();  // 米
            int duration = route.path("duration").asInt();  // 秒

            // 提取路径点
            List<double[]> path = new ArrayList<>();
            JsonNode steps = route.path("steps");
            if (steps.isArray()) {
                for (JsonNode step : steps) {
                    String polyline = step.path("polyline").asText();
                    // polyline格式: "lng,lat;lng,lat;..."
                    String[] points = polyline.split(";");
                    for (String point : points) {
                        String[] coords = point.split(",");
                        if (coords.length == 2) {
                            path.add(new double[]{
                                    Double.parseDouble(coords[0]),
                                    Double.parseDouble(coords[1])
                            });
                        }
                    }
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("path", path);
            result.put("distance", distance);
            result.put("duration", duration);

            log.info("[Amap] 路线规划成功: {}米, {}秒, {}个点", distance, duration, path.size());
            return result;

        } catch (Exception e) {
            log.error("[Amap] 路线规划异常", e);
            return null;
        }
    }

    /**
     * 批量规划路线（用于多天行程）
     *
     * @param points 途经点列表 [{lat, lng, name}]
     * @return 每段路线的规划结果
     */
    public List<Map<String, Object>> planRoutes(List<Map<String, Object>> points) {
        List<Map<String, Object>> routes = new ArrayList<>();

        if (points == null || points.size() < 2) {
            return routes;
        }

        for (int i = 0; i < points.size() - 1; i++) {
            Map<String, Object> start = points.get(i);
            Map<String, Object> end = points.get(i + 1);

            double startLng = getDoubleValue(start, "lng");
            double startLat = getDoubleValue(start, "lat");
            double endLng = getDoubleValue(end, "lng");
            double endLat = getDoubleValue(end, "lat");

            Map<String, Object> route = planDrivingRoute(startLng, startLat, endLng, endLat);
            if (route != null) {
                route.put("startName", start.get("name"));
                route.put("endName", end.get("name"));
                routes.add(route);
            }
        }

        return routes;
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
}
