package com.travel.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 工具：Overpass API（OpenStreetMap）
 * 完全免费，无需API Key
 * 用途：查询全球景点、餐厅、酒店信息（替代OpenTripMap）
 */
@Slf4j
@Component
public class OverpassApiTool {

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(name = "overpass_query", description = """
            从OpenStreetMap查询全球景点、餐厅、酒店信息。

            【重要】这是 search_place 工具失败后的降级方案（用于国外目的地）！

            调用时机：
            - search_place 查询结果为空时（尤其是国外城市）
            - 用户询问国外目的地景点时
            - 需要获取景点的详细介绍、坐标等信息

            参数说明：
            - city：城市名称（必填），如"Paris"、"Tokyo"、"三亚"、"北京"
            - type：地点类型（可选，默认tourist_attraction）
               * tourist_attraction - 旅游景点（默认）
               * restaurant - 餐厅
               * hotel - 酒店
               * museum - 博物馆
               * park - 公园
               * cinema - 电影院
               * shopping_mall - 购物中心

            返回：景点列表，包含名称、类型、坐标等

            ⚠️ 国内目的地优先用 search_place，国外目的地才用此工具！
            """)
    public String queryPlaces(
            @ToolParam(description = "城市名称，如'Paris'、'Tokyo'、'三亚'、'北京'") String city,
            @ToolParam(description = "地点类型：tourist_attraction=景点(默认), restaurant=餐厅, hotel=酒店, museum=博物馆, park=公园", required = false) String type) {

        if (city == null || city.trim().isEmpty()) {
            return "❌ 城市名称不能为空！";
        }

        try {
            String placeType = type != null && !type.trim().isEmpty() ? type.trim() : "tourist_attraction";

            // Step 1: 获取城市坐标（使用Nominatim API）
            String searchCity = city.trim();
            String nominatimUrl = String.format(
                    "https://nominatim.openstreetmap.org/search?format=json&q=%s&limit=1",
                    URLEncoder.encode(searchCity, StandardCharsets.UTF_8)
            );

            Request nominatimRequest = new Request.Builder()
                    .url(nominatimUrl)
                    .header("User-Agent", "AiTravelGuide/1.0")
                    .build();

            double lat = 0, lon = 0;
            try (Response nominatimResponse = httpClient.newCall(nominatimRequest).execute()) {
                if (nominatimResponse.isSuccessful() && nominatimResponse.body() != null) {
                    String nominatimBody = nominatimResponse.body().string();
                    JsonNode nominatimJson = objectMapper.readTree(nominatimBody);
                    if (nominatimJson.isArray() && nominatimJson.size() > 0) {
                        lat = nominatimJson.get(0).get("lat").asDouble();
                        lon = nominatimJson.get(0).get("lon").asDouble();
                        log.info("[OverpassApiTool] 坐标解析成功: city={}, lat={}, lon={}", city, lat, lon);
                    }
                }
            }

            if (lat == 0 && lon == 0) {
                return String.format("❌ 无法解析城市【%s】的坐标，请检查城市名称是否正确。", city);
            }

            // Step 2: 构建Overpass QL查询
            String osmTag = convertToOsmTag(placeType);
            // 查询周边约10km范围
            double delta = 0.1;
            String overpassQuery = String.format(
                    "[out:json][timeout:25];" +
                    "(" +
                    "  node[%s](%.6f,%.6f,%.6f,%.6f);" +
                    "  way[%s](%.6f,%.6f,%.6f,%.6f);" +
                    "  relation[%s](%.6f,%.6f,%.6f,%.6f);" +
                    ");" +
                    "out body;" +
                    ">; " +
                    "out skel qt;",
                    osmTag, lat - delta, lon - delta, lat + delta, lon + delta,
                    osmTag, lat - delta, lon - delta, lat + delta, lon + delta,
                    osmTag, lat - delta, lon - delta, lat + delta, lon + delta
            );

            String overpassUrl = "https://overpass-api.de/api/interpreter?data=" + 
                    overpassQuery.replace(" ", "%20");

            Request overpassRequest = new Request.Builder().url(overpassUrl).build();
            try (Response overpassResponse = httpClient.newCall(overpassRequest).execute()) {
                if (!overpassResponse.isSuccessful()) {
                    return String.format("❌ Overpass API查询失败，HTTP状态码：%d", overpassResponse.code());
                }

                String overpassBody = overpassResponse.body().string();
                JsonNode overpassJson = objectMapper.readTree(overpassBody);

                JsonNode elements = overpassJson.path("elements");
                if (!elements.isArray() || elements.isEmpty()) {
                    return String.format("未找到城市【%s】的%s相关信息。", city, placeType);
                }

                // 解析结果
                StringBuilder sb = new StringBuilder();
                String typeName = getTypeName(placeType);
                sb.append(String.format("🌍 %s - %s（数据来源：OpenStreetMap）\n\n", city, typeName));

                int count = 0;
                for (JsonNode element : elements) {
                    if (count >= 20) break; // 最多返回20条

                    String name = element.path("tags").path("name").asText("");
                    if (name.isEmpty()) continue;

                    double elementLat = element.path("lat").asDouble(0);
                    double elementLon = element.path("lon").asDouble(0);
                    if (elementLat == 0 && elementLon == 0) continue;

                    sb.append(String.format("%d. **%s**\n", ++count, name));

                    // 获取详细信息
                    String address = element.path("tags").path("addr:full").asText("");
                    if (address.isEmpty()) {
                        address = element.path("tags").path("addr:street").asText("");
                    }
                    if (!address.isEmpty()) {
                        sb.append(String.format("   地址：%s\n", address));
                    }

                    String phone = element.path("tags").path("phone").asText("");
                    if (!phone.isEmpty()) {
                        sb.append(String.format("   电话：%s\n", phone));
                    }

                    String website = element.path("tags").path("website").asText("");
                    if (!website.isEmpty()) {
                        sb.append(String.format("   网站：%s\n", website));
                    }

                    sb.append(String.format("   坐标：%.6f, %.6f\n", elementLat, elementLon));
                    sb.append("\n");
                }

                if (count == 0) {
                    return String.format("未找到城市【%s】的%s相关信息。", city, typeName);
                }

                sb.append(String.format("✅ 共找到 %d 个结果（显示前20个）\n", count));
                sb.append("\n💡 数据来源：OpenStreetMap（完全免费，无需API Key）");

                log.info("[OverpassApiTool] 查询成功: city={}, type={}, count={}", city, placeType, count);
                return sb.toString();
            }
        } catch (Exception e) {
            log.error("[OverpassApiTool] 查询失败: city={}", city, e);
            return "❌ 查询失败：" + e.getMessage() + "。请稍后再试。";
        }
    }

    /**
     * 将类型转换为OSM标签
     */
    private String convertToOsmTag(String type) {
        return switch (type) {
            case "tourist_attraction" -> "\"tourism\"=\"attraction\"";
            case "restaurant" -> "\"amenity\"=\"restaurant\"";
            case "hotel" -> "\"tourism\"=\"hotel\"";
            case "museum" -> "\"tourism\"=\"museum\"";
            case "park" -> "\"leisure\"=\"park\"";
            case "cinema" -> "\"amenity\"=\"cinema\"";
            case "shopping_mall" -> "\"shop\"=\"mall\"";
            default -> "\"tourism\"=\"attraction\"";
        };
    }

    /**
     * 获取类型中文名
     */
    private String getTypeName(String type) {
        return switch (type) {
            case "tourist_attraction" -> "旅游景点";
            case "restaurant" -> "餐厅";
            case "hotel" -> "酒店";
            case "museum" -> "博物馆";
            case "park" -> "公园";
            case "cinema" -> "电影院";
            case "shopping_mall" -> "购物中心";
            default -> "景点";
        };
    }
}
