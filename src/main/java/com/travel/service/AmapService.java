package com.travel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 高德地图服务
 */
@Slf4j
@Service
public class AmapService {

    @Value("${amap.web-key}")
    private String webKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public AmapService(org.springframework.boot.web.client.RestTemplateBuilder builder, ObjectMapper objectMapper) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(30))
                .setReadTimeout(Duration.ofSeconds(60))
                .build();
        this.objectMapper = objectMapper;
    }

    private static final String DIRECTION_API = "https://restapi.amap.com/v3/direction/driving";
    private static final String GEOCODE_API = "https://restapi.amap.com/v3/geocode/geo";
    private static final String REGEODE_API = "https://restapi.amap.com/v3/geocode/regeo";

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

    /**
     * 地理编码 - 根据地址获取经纬度
     *
     * @param address 详细地址
     * @param city    城市名（可选，用于提高准确性）
     * @return 经纬度数组 [lng, lat]，失败返回 null
     */
    public double[] geocode(String address, String city) {
        if (address == null || address.trim().isEmpty()) {
            log.warn("[Amap] 地理编码失败：地址为空");
            return null;
        }

        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(GEOCODE_API)
                    .queryParam("key", webKey)
                    .queryParam("address", address)
                    .queryParam("output", "json");

            if (city != null && !city.trim().isEmpty()) {
                builder.queryParam("city", city);
            }

            String url = builder.toUriString();
            log.info("[Amap] 地理编码请求: address={}, city={}", address, city);

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());

            if (!"1".equals(root.path("status").asText())) {
                String info = root.path("info").asText();
                log.error("[Amap] 地理编码失败: {}", info);
                return null;
            }

            JsonNode geocodes = root.path("geocodes");
            if (!geocodes.isArray() || geocodes.size() == 0) {
                log.warn("[Amap] 地理编码无结果: address={}", address);
                return null;
            }

            // 取第一个结果
            JsonNode firstResult = geocodes.get(0);
            String location = firstResult.path("location").asText();

            // location格式: "lng,lat"
            String[] coords = location.split(",");
            if (coords.length == 2) {
                double lng = Double.parseDouble(coords[0]);
                double lat = Double.parseDouble(coords[1]);
                log.info("[Amap] 地理编码成功: {} -> [{}, {}]", address, lng, lat);
                return new double[]{lng, lat};
            }

            return null;

        } catch (Exception e) {
            log.error("[Amap] 地理编码异常: address={}", address, e);
            return null;
        }
    }

    /**
     * 地理编码 - 根据地址获取经纬度（不带城市参数）
     */
    public double[] geocode(String address) {
        return geocode(address, null);
    }

    /**
     * 逆地理编码 - 根据经纬度获取详细地址
     *
     * @param lng 经度
     * @param lat 纬度
     * @return 格式化的详细地址，失败返回 null
     */
    public String regeocode(double lng, double lat) {
        try {
            String location = String.format("%.6f,%.6f", lng, lat);
            String url = UriComponentsBuilder.fromHttpUrl(REGEODE_API)
                    .queryParam("key", webKey)
                    .queryParam("location", location)
                    .queryParam("extensions", "base")
                    .queryParam("output", "json")
                    .toUriString();

            log.info("[Amap] 逆地理编码请求: location={}", location);

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());

            if (!"1".equals(root.path("status").asText())) {
                String info = root.path("info").asText();
                log.error("[Amap] 逆地理编码失败: {}", info);
                return null;
            }

            JsonNode regeocode = root.path("regeocode");
            if (regeocode.isMissingNode() || "0".equals(regeocode.path("status").asText())) {
                log.warn("[Amap] 逆地理编码无结果: location={}", location);
                return null;
            }

            // 优先取 formatted_address（完整地址），fallback 到 province+city+district+township+street
            String formattedAddress = regeocode.path("formatted_address").asText(null);
            if (formattedAddress != null && !formattedAddress.isEmpty()) {
                log.info("[Amap] 逆地理编码成功: [{},{}] -> {}", lng, lat, formattedAddress);
                return formattedAddress;
            }

            // 拼装式地址
            String province = regeocode.path("addressComponent").path("province").asText("");
            String city = regeocode.path("addressComponent").path("city").asText("");
            String district = regeocode.path("addressComponent").path("district").asText("");
            String township = regeocode.path("addressComponent").path("township").asText("");
            String street = regeocode.path("addressComponent").path("streetNumber").path("street").asText("");
            String number = regeocode.path("addressComponent").path("streetNumber").path("number").asText("");

            StringBuilder sb = new StringBuilder();
            if (!province.isEmpty()) sb.append(province);
            if (!city.isEmpty()) sb.append(city);
            if (!district.isEmpty()) sb.append(district);
            if (!township.isEmpty()) sb.append(township);
            if (!street.isEmpty()) sb.append(street);
            if (!number.isEmpty()) sb.append(number);

            String address = sb.toString();
            log.info("[Amap] 逆地理编码成功（拼装）: [{},{}] -> {}", lng, lat, address);
            return address.isEmpty() ? null : address;

        } catch (Exception e) {
            log.error("[Amap] 逆地理编码异常: [lng={}, lat={}]", lng, lat, e);
            return null;
        }
    }

    /**
     * 逆地理编码 - 支持传入 String 类型坐标
     */
    public String regeocode(String location) {
        if (location == null || location.trim().isEmpty()) {
            return null;
        }
        try {
            String[] parts = location.split(",");
            if (parts.length == 2) {
                return regeocode(Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()));
            }
        } catch (NumberFormatException e) {
            log.warn("[Amap] 坐标格式错误: {}", location);
        }
        return null;
    }

}
