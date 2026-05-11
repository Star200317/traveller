package com.travel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.entity.Place;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
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
    private final PlaceService placeService;

    public AmapService(org.springframework.boot.web.client.RestTemplateBuilder builder, 
                       ObjectMapper objectMapper,
                       PlaceService placeService) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(30))
                .setReadTimeout(Duration.ofSeconds(60))
                .build();
        this.objectMapper = objectMapper;
        this.placeService = placeService;
    }

    private static final String DIRECTION_API = "https://restapi.amap.com/v3/direction/driving";
    private static final String GEOCODE_API = "https://restapi.amap.com/v3/geocode/geo";
    private static final String REGEODE_API = "https://restapi.amap.com/v3/geocode/regeo";
    private static final String POI_SEARCH_API = "https://restapi.amap.com/v3/place/text";

    /**
     * 规划驾驶路线（带重试机制和请求间隔）
     *
     * @param originLng 起点经度
     * @param originLat 起点纬度
     * @param destLng   终点经度
     * @param destLat   终点纬度
     * @return 路线信息 {path: [[lng,lat],...], distance: 米, duration: 秒}
     */
    public Map<String, Object> planDrivingRoute(double originLng, double originLat, double destLng, double destLat) {
        // 请求间隔控制：避免触发高德API限流（免费版QPS=200/秒）
        try {
            Thread.sleep(100); // 每次请求间隔100ms
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // 重试机制：最多重试3次
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
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

                log.info("[Amap] 请求路线规划(第{}次): {} -> {}", attempt, origin, destination);

                ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
                JsonNode root = objectMapper.readTree(response.getBody());

                if (!"1".equals(root.path("status").asText())) {
                    String info = root.path("info").asText();
                    
                    // 限流错误处理：等待后重试
                    if ("CUQPS_HAS_EXCEEDED_THE_LIMIT".equals(info) && attempt < maxRetries) {
                        log.warn("[Amap] 路线规划限流，{}秒后重试(第{}次)...", attempt * 2, attempt);
                        try {
                            Thread.sleep(attempt * 2000L); // 递增等待时间：2秒、4秒、6秒
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        continue; // 重试
                    }
                    
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
            // 网络异常等，如果不是最后一次尝试则重试
            if (attempt < maxRetries) {
                log.warn("[Amap] 路线规划异常(第{}次)，1秒后重试...", attempt, e.getMessage());
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                continue;
            }
            log.error("[Amap] 路线规划异常(所有重试均失败)", e);
            return null;
        }
    }
    
    // 所有重试均失败
    log.error("[Amap] 路线规划失败：超过最大重试次数({})", maxRetries);
    return null;
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
    /**
     * 地理编码 - 根据地址获取经纬度（带城市参数）
     * 
     * 【重要】高德Geocode API的坑：
     * - 当address是POI名称(如"厦门大学")时，传city参数会触发ENGINE_RESPONSE_DATA_ERROR！
     * - 只有完整结构化地址(如"思明区中山路128号")才能安全配合city参数使用
     * 
     * 策略：
     * 1. 先用 city+address 合并为单一参数尝试（不传city查询参数）→ 兼容POI名称
     * 2. 如果失败，再拆分为 address+city 分开传 → 兼容结构化地址
     */
    public double[] geocode(String address, String city) {
        if (address == null || address.trim().isEmpty()) {
            log.warn("[Amap] 地理编码失败：地址为空");
            return null;
        }

        // 策略1：城市前缀合并到address中，不传city查询参数（兼容POI名称）
        double[] result = doGeocode(address, city, true);
        if (result != null) {
            return result;
        }

        // 策略2：拆分为独立参数（兼容结构化地址）
        log.debug("[Amap] 策略1失败，尝试策略2（分离city参数）: address={}, city={}", address, city);
        result = doGeocode(address, city, false);
        return result; // 即使null也返回
    }

    /**
     * 执行地理编码API调用
     * @param address 地址
     * @param city 城市
     * @param mergeCityToAddr true=把城市前缀到address中不传city参数 / false=分开传city参数
     */
    private double[] doGeocode(String address, String city, boolean mergeCityToAddr) {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(GEOCODE_API)
                    .queryParam("key", webKey)
                    .queryParam("output", "json");

            String effectiveAddress;
            if (mergeCityToAddr && city != null && !city.trim().isEmpty()) {
                // 城市前缀到address，不传city参数 → 解决POI名称+city报错的问题
                effectiveAddress = address.contains(city) ? address : (city + address);
            } else {
                // 传统方式：address和city分开传
                effectiveAddress = address;
                if (city != null && !city.trim().isEmpty()) {
                    builder.queryParam("city", city);
                }
            }
            builder.queryParam("address", effectiveAddress);

            String url = builder.toUriString();
            log.info("[Amap] 地理编码请求: address={}, city={}, mergeMode={}", effectiveAddress, city, mergeCityToAddr);

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

    /**
     * POI 关键字搜索 - 通过名称搜索地点，获取精确门牌号地址和坐标
     * 
     * 【城市安全保证】多结果智能筛选：
     * 1. 取前10个结果（而非只取第1个）
     * 2. 筛选 cityname/pname 匹配目标城市的条目
     * 3. 在匹配结果中取距离最近的（或相关度最高的）
     * 4. 如果没有任何结果在目标城市 → 返回null（拒绝跨城结果）
     *
     * @param keyword 搜索关键词（如"鼓浪屿"、"沙茶面"、"如家酒店"）
     * @param city    城市名（如"厦门"，强制约束搜索范围）
     * @return POI信息 {name, address, lng, lat}，失败返回null
     */
    public Map<String, Object> searchPOI(String keyword, String city) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return null;
        }
        // 【关键修复】清理特殊字符，避免高德API返回INVALID_PARAMS
        // 已知问题字符：·（中间点）、全角括号（）等
        String cleanKeyword = sanitizeKeyword(keyword);
        
        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(POI_SEARCH_API)
                    .queryParam("key", webKey)
                    .queryParam("keywords", cleanKeyword)  // 使用清理后的关键词
                    .queryParam("output", "json")
                    .queryParam("offset", "10")   // 取前10个结果用于智能筛选
                    .queryParam("page", "1")
                    .queryParam("extensions", "base"); // 返回基础信息

            if (city != null && !city.trim().isEmpty()) {
                builder.queryParam("city", city);
            }

            String url = builder.toUriString();
            log.info("[Amap] POI搜索请求(智能筛选): keyword={}, clean={}, city={}", keyword, cleanKeyword, city);

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());

            if (!"1".equals(root.path("status").asText())) {
                String info = root.path("info").asText();
                log.warn("[Amap] POI搜索失败: {}", info);
                return null;
            }

            JsonNode pois = root.path("pois");
            if (!pois.isArray() || pois.size() == 0) {
                log.warn("[Amap] POI搜索无结果: keyword={}", keyword);
                return null;
            }

            // 【核心】从多个结果中智能选择目标城市的最佳匹配
            Map<String, Object> bestMatch = selectBestPOIMatch(pois, keyword, city);
            if (bestMatch != null) {
                log.info("[Amap] ✅ POI智能筛选命中: 「{}」→「{}」 [{},{}]",
                        keyword, bestMatch.get("fullAddress"), bestMatch.get("lng"), bestMatch.get("lat"));

                // 【入库】将POI搜索结果存入数据库（异步，不阻塞主流程）
                savePOIToDatabase(bestMatch, city);
            } else {
                log.warn("[Amap] ⚠️ POI搜索有{}个结果但无一个在目标城市「{}」, keyword={}", pois.size(), city, keyword);
            }
            return bestMatch;

        } catch (Exception e) {
            log.error("[Amap] POI搜索异常: keyword={}, city={}", keyword, city, e);
            return null;
        }
    }

    /**
     * 从POI搜索结果中智能选择最佳匹配项
     * 
     * 策略：
     * 1. 先筛选 cityname/pname 包含目标城市名的条目
     * 2. 如果有匹配项 → 取距离最近的（按高德返回的distance字段排序）
     * 3. 如果没有匹配项但有city参数 → 返回null（拒绝跨城结果！）
     * 4. 如果没有传city → 取第一个（降级为旧行为）
     */
    private Map<String, Object> selectBestPOIMatch(JsonNode pois, String keyword, String city) {
        List<Map<String, Object>> candidates = new ArrayList<>();

        for (int i = 0; i < pois.size() && candidates.size() < 10; i++) {
            JsonNode poi = pois.get(i);
            String pname = poi.path("pname").asText("");  // 省份
            String cityname = poi.path("cityname").asText("");  // 城市
            String adname = poi.path("adname").asText("");  // 区/县
            String name = poi.path("name").asText("");
            String address = poi.path("address").asText("");
            String locationStr = poi.path("location").asText("");
            
            // 跳过无坐标的结果
            if (locationStr == null || locationStr.isEmpty()) continue;

            String[] coords = locationStr.split(",");
            if (coords.length != 2) continue;

            double lng = Double.parseDouble(coords[0]);
            double lat = Double.parseDouble(coords[1]);

            // 获取距离信息（高德返回的离搜索中心的距离，单位米）
            int distance = poi.has("distance") ? poi.path("distance").asInt(Integer.MAX_VALUE) : Integer.MAX_VALUE;

            Map<String, Object> item = new HashMap<>();
            item.put("name", name);
            item.put("address", address);
            item.put("lng", lng);
            item.put("lat", lat);
            item.put("distance", distance);
            item.put("pname", pname);
            item.put("cityname", cityname);
            item.put("adname", adname);

            // 构建完整地址
            String fullAddress = address;
            if (!adname.isEmpty() && !address.startsWith(adname)) {
                fullAddress = adname + address;
            }
            if (!cityname.isEmpty() && !fullAddress.contains(cityname)) {
                fullAddress = cityname + fullAddress;
            }
            item.put("fullAddress", fullAddress);

            candidates.add(item);
        }

        // 没有有效候选 → 返回null
        if (candidates.isEmpty()) return null;

        // --- 场景A：指定了目标城市 ---
        if (city != null && !city.trim().isEmpty()) {
            String targetCity = city.trim();

            // 筛选目标城市的候选
            List<Map<String, Object>> cityMatches = new ArrayList<>();
            for (Map<String, Object> c : candidates) {
                String cName = (String) c.get("cityname");
                String pName = (String) c.get("pname");
                // 城市名匹配 或 省级市名称包含关系（如"厦门市"/"厦门"）
                if ((cName != null && (cName.contains(targetCity) || targetCity.contains(cName)))
                        || (pName != null && pName.contains(targetCity))) {
                    cityMatches.add(c);
                }
            }

            if (!cityMatches.isEmpty()) {
                // 有城市匹配项 → 取距离最近的
                cityMatches.sort((a, b) -> {
                    int da = (Integer) a.getOrDefault("distance", Integer.MAX_VALUE);
                    int db = (Integer) b.getOrDefault("distance", Integer.MAX_VALUE);
                    return Integer.compare(da, db);
                });
                
                Map<String, Object> chosen = cityMatches.get(0);
                log.info("[Amap] POI筛选: {}个结果中{}个在「{}」, 选择最近: 「{}」({}m)",
                        candidates.size(), cityMatches.size(), targetCity,
                        chosen.get("fullAddress"), chosen.get("distance"));
                return chosen;
            }

            // 有city参数但没有一个结果在目标城市 → 严格拒绝！
            log.error("[Amap] ⛔ POI跨城风险! keyword=「{}」, 目标城市=「{}」, 但所有{}个结果都不在该城市:",
                    keyword, targetCity, candidates.size());
            for (Map<String, Object> c : candidates) {
                log.error("   - 「{}」 ({},{}) [省:{} 市:{}]",
                        c.get("fullAddress"), c.get("lng"), c.get("lat"),
                        c.get("pname"), c.get("cityname"));
            }
            return null; // 宁可没结果也不要错误城市的坐标！
        }

        // --- 场景B：未指定城市 → 降级为取第一个（旧行为）---
        return candidates.get(0);
    }

    /**
     * 【城市安全校验】验证坐标是否在目标城市内
     * 
     * 通过逆地理编码获取坐标的实际城市名，与期望城市对比。
     * 防止"厦门大学坐标在北京"这类跨城错误。
     *
     * @param lng        经度
     * @param lat        纬度
     * @param expectCity 期望的城市名（如"厦门"、"厦门市"）
     * @return true=在目标城市内 / false=不在或无法确定
     */
    public boolean verifyCityMatch(double lng, double lat, String expectCity) {
        if (expectCity == null || expectCity.trim().isEmpty()) {
            return true; // 未指定城市，跳过校验
        }

        try {
            String location = String.format("%.6f,%.6f", lng, lat);
            String url = UriComponentsBuilder.fromHttpUrl(REGEODE_API)
                    .queryParam("key", webKey)
                    .queryParam("location", location)
                    .queryParam("extensions", "base")
                    .queryParam("output", "json")
                    .toUriString();

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());

            if (!"1".equals(root.path("status").asText())) {
                log.warn("[Amap] 城市校验-逆地理编码失败: ({},{})", lng, lat);
                return false;
            }

            JsonNode addrComponent = root.path("regeocode").path("addressComponent");
            String actualProvince = addrComponent.path("province").asText("");
            String actualCity = addrComponent.path("city").asText("");
            String actualDistrict = addrComponent.path("district").asText("");

            // 高德对直辖市（北京/上海/天津/重庆）的city字段可能返回空数组[]，用province代替
            String effectiveCity = (actualCity == null || actualCity.isEmpty() || "[]".equals(actualCity))
                    ? actualProvince : actualCity;

            // 检查匹配：期望城市包含在实际城市中 或 实际城市包含在期望中
            // 兼容 "厦门"/"厦门市" 等变体
            boolean match = (effectiveCity != null && (
                    effectiveCity.contains(expectCity) 
                    || expectCity.contains(effectiveCity.replace("市", ""))
                    || effectiveCity.replace("市", "").contains(expectCity)));

            if (!match) {
                // 再检查省份级别（如用户传"福建"，实际是"福建省"）
                if (expectCity.length() >= 2 && actualProvince != null
                        && (actualProvince.contains(expectCity) || expectCity.contains(actualProvince))) {
                    match = true;
                }
            }

            if (match) {
                log.debug("[Amap] ✅ 城市校验通过: ({},{}) → {} [期望:{}]", 
                        lng, lat, effectiveCity, expectCity);
            } else {
                log.error("[Amap] ⛔ 城市校验失败! 坐标({},{}) 实际位置: {}-{}-{} [期望城市:{}] → 跨城风险!",
                        lng, lat, actualProvince, effectiveCity, actualDistrict, expectCity);
            }

            return match;

        } catch (Exception e) {
            log.error("[Amap] 城市校验异常: ({},{}) 期望={}", lng, lat, expectCity, e);
            return false;
        }
    }

    /**
     * 地址精确化补全（硬性门牌号要求 + 城市安全约束）
     *
     * 铁律：
     * 1. 返回的地址必须精确到门牌号级别，否则 precise=false
     * 2. 返回的坐标必须在目标城市内，否则拒绝使用
     * 流程：【优先查数据库】→ 地理编码(+城市校验) → POI搜索(+城市筛选) → 仍然失败则标记为不精确
     *
     * @return {address, lng, lat, source, precise}
     */
    public Map<String, Object> enrichPreciseAddress(String name, String address, String city) {
        Map<String, Object> result = new HashMap<>();

        // ===== 0) 【优先】从数据库查询地点数据 =====
        Place dbPlace = placeService.findByName(name, null);
        if (dbPlace != null) {
            BigDecimal dbLat = dbPlace.getLatitude();
            BigDecimal dbLng = dbPlace.getLongitude();
            String dbAddress = dbPlace.getAddress();

            if (dbLat != null && dbLng != null && dbLng.doubleValue() != 0 && dbLat.doubleValue() != 0
                    && dbAddress != null && !dbAddress.isEmpty()) {
                // 数据库中有完整数据：地址+坐标
                if (!isVagueAddress(dbAddress)) {
                    // 数据库地址是精确的，直接使用
                    result.put("lng", dbLng.doubleValue());
                    result.put("lat", dbLat.doubleValue());
                    result.put("address", dbAddress);
                    result.put("source", "database");
                    result.put("precise", true);
                    log.info("[Amap] ✅ 数据库命中+地址精确: 「{}」→「{}」({},{})", name, dbAddress, dbLng, dbLat);
                    return result;
                } else {
                    // 数据库地址不精确，但有坐标，保留备用
                    log.info("[Amap] 📋 数据库命中但地址不精确: 「{}」地址={}, 备用坐标({},{})",
                            name, dbAddress, dbLng, dbLat);
                    // 不提前return，继续往下走POI搜索
                }
            }
        }

        // 1) 先尝试标准地理编码获取坐标
        String searchAddr = (address != null && !address.isEmpty()) ? address : name;
        String geocodeAddr = searchAddr;
        if (city != null && !city.isEmpty() && !searchAddr.contains(city)) {
            geocodeAddr = city + searchAddr;
        }

        double[] geoCoords = geocode(geocodeAddr, city);
        boolean isVague = isVagueAddress(address);

        // 1a) 地理编码成功 + 地址精确 → 【城市校验】→ 通过后才使用
        if (geoCoords != null && !isVague) {
            if (verifyCityMatch(geoCoords[0], geoCoords[1], city)) {
                result.put("lng", geoCoords[0]);
                result.put("lat", geoCoords[1]);
                result.put("address", address);
                result.put("source", "geocode");
                result.put("precise", true);
                log.info("[Amap] ✅ 地址精确+城市校验通过: [{}] → ({},{})", address, geoCoords[0], geoCoords[1]);
                return result;
            }
            // 城市校验失败！坐标不在目标城市 → 降级走POI搜索（可能AI给的地址带了错误城市信息）
            log.warn("[Amap] ⚠️ 地理编码坐标({},{})未通过城市「{}」校验，降级走POI名称搜索...", 
                    geoCoords[0], geoCoords[1], city);
        }

        // 2) 地址模糊 或地理编码失败/城市不匹配 → 【强制】POI名称搜索获取精确门牌号
        //    searchPOI内部已有多结果城市筛选机制（selectBestPOIMatch），只返回目标城市的结果
        //    【优化】不再对POI结果做逆地理编码二次校验，因为selectBestPOIMatch已按cityname严格筛选
        if (name != null && !name.trim().isEmpty()) {
            Map<String, Object> poiResult = searchPOI(name, city);
            if (poiResult != null) {
                // 直接信任POI搜索结果（已通过selectBestPOIMatch的城市筛选），不再做verifyCityMatch
                Double poiLng = (Double) poiResult.get("lng");
                Double poiLat = (Double) poiResult.get("lat");
                result.put("lng", poiLng);
                result.put("lat", poiLat);
                result.put("address", poiResult.getOrDefault("fullAddress",
                                poiResult.getOrDefault("address", address)));
                result.put("source", "poi_search");
                result.put("precise", true);
                log.info("[Amap] ✅ POI精确门牌号命中: 「{}」→「{}」({},{}) [{}]",
                        name, result.get("address"), poiLng, poiLat,
                        poiResult.getOrDefault("cityname", ""));
                return result;
            }

            // POI精确匹配失败，尝试放宽关键词（去掉后缀再搜一次）
            String shortName = shortenSearchKeyword(name);
            if (shortName != null && !shortName.equals(name)) {
                Map<String, Object> poiResult2 = searchPOI(shortName, city);
                if (poiResult2 != null) {
                    result.put("lng", poiResult2.get("lng"));
                    result.put("lat", poiResult2.get("lat"));
                    result.put("address", poiResult2.getOrDefault("fullAddress",
                                    poiResult2.getOrDefault("address", address)));
                    result.put("source", "poi_search_relaxed");
                    result.put("precise", true);
                    log.info("[Amap] ✅ 放宽关键词「{}」→ POI精确门牌号「{}」({},{})",
                            shortName, result.get("address"), result.get("lng"), result.get("lat"));
                    return result;
                }
            }
        }

        // 3) 全部失败 — 有坐标但地址不精确 → 标记为不精确，调用方应拒绝或特殊处理
        if (geoCoords != null) {
            result.put("lng", geoCoords[0]);
            result.put("lat", geoCoords[1]);
            result.put("address", "⚠️ 地址未精确到门牌号（" + 
                        (address != null ? address : "无地址") + "）");
            result.put("originalAddress", address); // 保留原始模糊地址供参考
            result.put("source", "geocode_vague");
            result.put("precise", false);
            log.warn("[Amap] ❌ 无法获取精确门牌号: name={}, 原始地址={}, 仅保留区域坐标({},{})",
                    name, address, geoCoords[0], geoCoords[1]);
            return result;
        }

        // 完全失败
        result.put("source", "none");
        result.put("precise", false);
        result.put("address", "⚠️ 地址缺失且无法自动定位");
        log.error("[Amap] ❌❌ 地址完全无法解析: name={}, address={}", name, address);
        return result;
    }

    /**
     * 清理POI搜索关键词中的特殊字符，避免高德API返回INVALID_PARAMS
     * 
     * 已知问题字符：
     * · (中间点/U+00B7) → 高德POI搜索不支持
     * 全角括号 （）→ 可能导致参数解析异常
     * 连续空格 → 无意义且可能干扰匹配
     */
    private String sanitizeKeyword(String keyword) {
        if (keyword == null) return null;
        
        String cleaned = keyword
                .replaceAll("·", " ")       // 中间点 → 空格
                .replaceAll("[（）]", "")    // 全角括号 → 删除
                .replaceAll("\\s+", " ")   // 多个空格 → 单个空格
                .trim();
        
        // 如果清理后为空，返回原始keyword（总比没有好）
        if (cleaned.isEmpty()) return keyword;
        return cleaned;
    }

    /**
     * 缩短搜索关键词（去除常见干扰后缀，提高POI匹配率）
     * 如："鼓浪屿日光岩景区" → "鼓浪屿日光岩"
     */
    private String shortenSearchKeyword(String keyword) {
        if (keyword == null || keyword.length() <= 4) return keyword;
        // 去除尾部常见泛化后缀
        String shortened = keyword.replaceAll(
            "(?:景区|公园|风景区|旅游区|度假区|观光区|游览区|名胜区|自然保护区|森林公园|地质公园)$", "");
        return shortened.equals(keyword) ? null : shortened.trim();
    }

    /**
     * 将POI搜索结果存入数据库（查重后插入）
     * 不阻塞主流程，异常仅记录日志
     */
    private void savePOIToDatabase(Map<String, Object> poiResult, String city) {
        try {
            String name = (String) poiResult.get("name");
            String address = (String) poiResult.getOrDefault("fullAddress", poiResult.get("address"));
            Object lngObj = poiResult.get("lng");
            Object latObj = poiResult.get("lat");

            if (name == null || name.isEmpty()) return;

            BigDecimal lng = lngObj != null ? new BigDecimal(lngObj.toString()) : null;
            BigDecimal lat = latObj != null ? new BigDecimal(latObj.toString()) : null;

            // 根据名称猜测类型
            String type = guessPlaceType(name);

            placeService.findOrCreate(name, address, type, "", city, "", lat, lng, "web");
            log.debug("[Amap] 📍 POI结果已入库: name={}, address={}", name, address);
        } catch (Exception e) {
            log.warn("[Amap] ⚠️ POI入库失败: {}", e.getMessage());
        }
    }

    /**
     * 根据名称猜测地点类型
     */
    private String guessPlaceType(String name) {
        if (name == null) return "attraction";
        name = name.toLowerCase();
        if (name.contains("酒店") || name.contains("宾馆") || name.contains("客栈")
                || name.contains("民宿") || name.contains("公寓") || name.contains("招待所")) {
            return "hotel";
        }
        if (name.contains("餐厅") || name.contains("饭店") || name.contains("酒楼") || name.contains("食府")
                || name.contains("小吃") || name.contains("火锅") || name.contains("烧烤") || name.contains("面馆")
                || name.contains("咖啡") || name.contains("茶楼") || name.contains("酒吧")) {
            return "restaurant";
        }
        return "attraction";
    }

    /**
     * 判断地址是否精确到门牌号（硬性标准）
     * 
     * ✅ 精确地址特征（任一即通过）：
     *   - 含数字+门牌单位（22号、128-5号、3号楼、2层等）
     *   - 含具体建筑/POI名称（XX广场、XX大厦、XX酒店、XX店、XX市场、XX码头等）
     *   - 含具体地标（XX公园、XX景区、XX车站、XX学校、XX医院）
     *   - 地址长度≥10字符且含多个定位要素
     *
     * ❌ 模糊地址特征：
     *   - 只有"区/县 + 路名"（如"思明区环岛路"、"思明区开元路"）
     *   - 只有城市名或区域名（≤8字符短地址）
     *   - "XX附近"、"XX周边"、"XX一带"
     */
    private boolean isVagueAddress(String address) {
        if (address == null || address.trim().isEmpty()) {
            return true;
        }
        String addr = address.trim();

        // === 精确地址判定（任一命中即不模糊）===

        // 1) 数字门牌号: "22号"、"128-5号"、"3号楼"、"A栋201室"、 "1弄12支弄"
        if (addr.matches(".*\\d+[\\-–]?\\d*(?:号|号楼|栋|幢|层|室|单元|户|弄|巷|支弄|座|区|铺).*")) {
            return false; // 精确
        }

        // 2) 具体建筑/商业POI名称（高德POI数据库中常见的精确标识）
        if (addr.matches(".*(?:广场|大厦|商场|购物中心|百货| Mall |MALL|"
                + "酒店|宾馆|旅馆|民宿|客栈|公寓|招待所|"
                + "店|饭店|餐厅|馆|楼|坊|村|庄|苑|花园|新城|新村|小区|家园|"
                + "中心|基地|园区|产业园|科技园|创意园|工业园|"
                + "码头|港口|车站|机场|口岸|枢纽|"
                + "市场|集市|夜市|步行街|商业街|美食街|老街|古街|"
                + "寺|庙|观|庵|堂|宫|阁|塔|陵|墓|祠|院|署|衙|"
                + "馆|博物馆|图书馆|美术馆|剧院|体育馆|体育场|"
                + "大学|学院|学校|中学|小学|幼儿园|医院|卫生院|诊所|"
                + "银行|邮局|派出所|办事处|居委会).*$")) {
            return false; // 精确 — 有明确建筑/场所名称
        }

        // 3) 具体自然地标/景点
        if (addr.matches(".*(?:岛|山|湖|海|江|河|湾|沙滩|海滩|温泉|瀑布|峡谷|森林|"
                + "国家公园|自然保护区|风景名胜区|旅游度假区|"
                + "古镇|古城墙|古城|历史街区|名人故居|纪念馆|"
                + "植物园|动物园|海洋馆|游乐园|乐园|世界|影城).*$")) {
            return false; // 精确 — 具体自然/人文地标
        }

        // === 模糊地址判定 ===

        // 4) "XX附近"/"XX周边"/"XX一带"
        if (addr.matches(".*(?:附近|周边|一带|旁边|对面|路口|交叉口|尽头).*$")) {
            return true; // 模糊
        }

        // 5) 只有"市/区/县 + 路名"而无门牌号（最常见AI编造模式）
        if (addr.matches("^[\\u4e00-\\u9fa5]{2,6}(?:市|自治区)?[\\u4e00-\\u9fa5]{0,3}(?:区|县|旗)[\\u4e00-\\u9fa5]+路$")) {
            return true; // 模糊 — 如"思明区环岛路"、"思明区思明开元路"
        }

        // 6) 只有区域名（很短的地址大概率是模糊的）
        if (addr.length() <= 7 && !addr.contains("号") && !addr.contains("店") 
            && !addr.contains("楼") && !addr.contains("广场") && !addr.contains("酒店")
            && !addr.contains("公园") && !addr.contains("景区")) {
            return true; // 太短，不够精确
        }

        // 默认：不确定时倾向于认为模糊（宁可多补全一次也不要放过）
        return addr.length() <= 10 && !addr.matches(".*\\d+.*");
    }

}
