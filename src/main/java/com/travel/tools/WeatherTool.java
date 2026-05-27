package com.travel.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 工具：天气查询（高德天气API + Open-Meteo备选）
 * 查询目的地实时天气和未来3天预报
 */
@Slf4j
@Component
public class WeatherTool {

    @Value("${amap.web-key}")
    private String amapKey;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Open-Meteo 备用工具
    @Autowired(required = false)
    private OpenMeteoWeatherTool openMeteoWeatherTool;

    @Tool(name = "weather_query", description = """
            查询目的地的实时天气和未来3天天气预报（高德天气API + Open-Meteo 备用）。

            【强制】用户一提到目的地，必须立即调用此工具！
            调用时机：
            - 【最高优先级】用户提到任何目的地（如"我想去大理玩"）→ 立即调用
            - 用户直接问天气（如"三亚最近天气怎么样"）
            - 用户问穿搭建议（根据天气给出建议）

            ⚠️ 绝对禁止：
            - 不调用工具就编造天气信息！
            - 看到目的地名字但不调用此工具！

            参数说明：
            - city：城市名称（必填），如"三亚"、"北京"、"大理"
            - type：查询类型（可选）
              * forecast（默认）= 未来3天预报（推荐，行程规划用）
              * realtime = 只查实时天气

            返回格式：
            🌤️ 城市名 天气预报：
            【今天】☀️ 白天：晴，25°C | 🌙 夜间：多云，18°C
            【明天】...
            """)
    public String queryWeather(
            @ToolParam(description = "城市名称，如'三亚'、'北京'、'大理'") String city,
            @ToolParam(description = "查询类型：forecast=未来3天预报（默认），realtime=实时天气", required = false) String type) {

        if (city == null || city.trim().isEmpty()) {
            return "❌ 城市名称不能为空！请提供目的地城市。";
        }

        boolean isForecast = !"realtime".equalsIgnoreCase(type);

        try {
            // 先尝试预报（extensions=forecast），如果失败则使用实时天气
            if (isForecast) {
                String forecastResult = queryForecast(city);
                if (forecastResult != null && !forecastResult.startsWith("❌")) {
                    return forecastResult;
                }
                log.warn("[WeatherTool] 高德预报查询失败，尝试实时天气: city={}", city);

                // 尝试实时天气
                String realtimeResult = queryRealtime(city);
                if (realtimeResult != null && !realtimeResult.startsWith("❌")) {
                    return realtimeResult;
                }
                log.warn("[WeatherTool] 高德实时天气也失败，尝试Open-Meteo: city={}", city);

                // 最后尝试 Open-Meteo
                if (openMeteoWeatherTool != null) {
                    String openMeteoResult = openMeteoWeatherTool.queryWeather(city);
                    if (openMeteoResult != null && !openMeteoResult.startsWith("❌")) {
                        return openMeteoResult;
                    }
                }

                return "❌ 天气查询服务暂时不可用，请稍后再试。";
            }

            // 使用实时天气（extensions=base）
            String realtimeResult = queryRealtime(city);
            if (realtimeResult != null && !realtimeResult.startsWith("❌")) {
                return realtimeResult;
            }

            // 实时天气失败，尝试 Open-Meteo
            if (openMeteoWeatherTool != null) {
                return openMeteoWeatherTool.queryWeather(city);
            }

            return "❌ 天气查询服务暂时不可用，请稍后再试。";
        } catch (Exception e) {
            log.error("[WeatherTool] 查询失败: city={}", city, e);
            return "❌ 天气查询失败：" + e.getMessage() + "。请稍后再试。";
        }
    }

    /**
     * 查询天气预报（未来3天）
     */
    private String queryForecast(String city) throws java.io.UnsupportedEncodingException {
        String url = String.format(
                "https://restapi.amap.com/v3/weather/weatherInfo?key=%s&city=%s&extensions=forecast&output=JSON",
                amapKey,
                java.net.URLEncoder.encode(city.trim(), "UTF-8")
        );

        Request request = new Request.Builder().url(url).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return "❌ 天气查询失败，HTTP状态码：" + response.code();
            }

            String respBody = response.body().string();
            JsonNode root = objectMapper.readTree(respBody);

            String status = root.has("status") ? root.get("status").asText() : "";
            if (!"1".equals(status)) {
                return null; // 预报不可用，触发降级
            }

            JsonNode forecasts = root.get("forecasts");
            if (forecasts == null || !forecasts.isArray() || forecasts.isEmpty()) {
                return null; // 没有预报数据，触发降级
            }

            return formatForecastResult(forecasts, city);
        } catch (Exception e) {
            log.warn("[WeatherTool] 预报查询异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 查询实时天气
     */
    private String queryRealtime(String city) throws java.io.UnsupportedEncodingException {
        String url = String.format(
                "https://restapi.amap.com/v3/weather/weatherInfo?key=%s&city=%s&extensions=base&output=JSON",
                amapKey,
                java.net.URLEncoder.encode(city.trim(), "UTF-8")
        );

        Request request = new Request.Builder().url(url).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return "❌ 天气查询失败，HTTP状态码：" + response.code();
            }

            String respBody = response.body().string();
            JsonNode root = objectMapper.readTree(respBody);

            String status = root.has("status") ? root.get("status").asText() : "";
            if (!"1".equals(status)) {
                return "❌ 天气查询失败，请检查城市名称是否正确。";
            }

            JsonNode lives = root.get("lives");
            if (lives == null || !lives.isArray() || lives.isEmpty()) {
                return String.format("❌ 未找到城市【%s】的天气信息。", city);
            }

            return formatRealtimeResult(lives);
        } catch (Exception e) {
            return "❌ 天气查询失败：" + e.getMessage();
        }
    }

    /**
     * 格式化天气预报结果
     */
    private String formatForecastResult(JsonNode forecasts, String city) {
        StringBuilder sb = new StringBuilder();
        JsonNode forecast = forecasts.get(0);
        String cityName = forecast.has("city") ? forecast.get("city").asText() : city;
        sb.append("🌤️ ").append(cityName).append(" 天气预报：\n\n");

        JsonNode casts = forecast.get("casts");
        if (casts != null && casts.isArray()) {
            String[] weekDays = {"今天", "明天", "后天", "大后天"};
            for (int i = 0; i < Math.min(casts.size(), 4); i++) {
                JsonNode cast = casts.get(i);
                String dayTemp = cast.has("daytemp") ? cast.get("daytemp").asText() : "?";
                String nightTemp = cast.has("nighttemp") ? cast.get("nighttemp").asText() : "?";
                String dayWeather = cast.has("dayweather") ? cast.get("dayweather").asText() : "?";
                String nightWeather = cast.has("nightweather") ? cast.get("nightweather").asText() : "?";
                String wind = cast.has("daywind") ? cast.get("daywind").asText() : "?";
                String power = cast.has("daypower") ? cast.get("daypower").asText() : "?";

                sb.append("【").append(i < weekDays.length ? weekDays[i] : "第" + (i + 1) + "天").append("】\n");
                sb.append("   ☀️ 白天：").append(dayWeather).append("，").append(dayTemp).append("°C\n");
                sb.append("   🌙 夜间：").append(nightWeather).append("，").append(nightTemp).append("°C\n");
                sb.append("   💨 风向：").append(wind).append("风，风力").append(power).append("级\n\n");
            }
        }

        sb.append("💡 行程规划建议：请根据天气预报合理安排室内/室外活动。");
        return sb.toString();
    }

    /**
     * 格式化实时天气结果
     */
    private String formatRealtimeResult(JsonNode lives) {
        JsonNode live = lives.get(0);
        String cityName = live.has("city") ? live.get("city").asText() : "未知";
        String weather = live.has("weather") ? live.get("weather").asText() : "?";
        String temp = live.has("temperature") ? live.get("temperature").asText() : "?";
        String wind = live.has("winddirection") ? live.get("winddirection").asText() : "?";
        String power = live.has("windpower") ? live.get("windpower").asText() : "?";
        String humidity = live.has("humidity") ? live.get("humidity").asText() : "?";
        String reportTime = live.has("reporttime") ? live.get("reporttime").asText() : "";

        StringBuilder sb = new StringBuilder();
        sb.append("🌤️ ").append(cityName).append(" 实时天气：\n\n");
        sb.append("   天气状况：").append(weather).append("\n");
        sb.append("   当前温度：").append(temp).append("°C\n");
        sb.append("   风向风力：").append(wind).append("风，").append(power).append("级\n");
        sb.append("   湿度：").append(humidity).append("%\n");
        if (!reportTime.isEmpty()) {
            sb.append("   发布时间：").append(reportTime).append("\n");
        }
        sb.append("\n💡 天气预报暂不可用，以上为实时数据。");
        return sb.toString();
    }
}
