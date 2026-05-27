package com.travel.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * 工具：天气预报（Open-Meteo API - 免费无密钥）
 * 支持全球任意地点的天气预报
 */
@Slf4j
@Component
public class OpenMeteoWeatherTool {

    @Value("${amap.web-key}")
    private String amapKey;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(name = "open_meteo_weather", description = """
            查询全球任意城市的天气预报（未来7天）。

            使用场景：
            - 高德天气API不可用时的备选方案
            - 需要多天天气预报进行行程规划
            - 查询境外城市天气

            参数：
            - city：城市名称，如"厦门"、"东京"、"巴黎"（中文或英文均可）

            返回：未来3天的天气预报，包含最高/最低温度和天气状况
            """)
    public String queryWeather(String city) {
        if (city == null || city.trim().isEmpty()) {
            return "❌ 城市名称不能为空！";
        }

        try {
            // 1. 先通过高德地理编码获取经纬度
            double[] coords = getCoordinates(city);
            if (coords == null) {
                return "❌ 未找到城市【" + city + "】的坐标信息，请确认城市名称是否正确。";
            }
            double latitude = coords[0];
            double longitude = coords[1];
            log.info("[OpenMeteo] 城市 {} 的坐标: lat={}, lon={}", city, latitude, longitude);

            // 2. 调用 Open-Meteo 获取天气预报
            return getForecast(latitude, longitude, city);
        } catch (Exception e) {
            log.error("[OpenMeteo] 查询失败: city={}", city, e);
            return "❌ 天气查询失败：" + e.getMessage();
        }
    }

    /**
     * 通过高德地理编码获取城市坐标
     */
    private double[] getCoordinates(String city) throws Exception {
        String url = String.format(
                "https://restapi.amap.com/v3/geocode/geo?key=%s&address=%s&output=JSON",
                amapKey,
                URLEncoder.encode(city.trim(), "UTF-8")
        );

        Request request = new Request.Builder().url(url).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("[OpenMeteo] 高德地理编码失败: HTTP {}", response.code());
                return null;
            }

            String body = response.body().string();
            JsonNode root = objectMapper.readTree(body);

            String status = root.has("status") ? root.get("status").asText() : "";
            if (!"1".equals(status)) {
                log.warn("[OpenMeteo] 高德地理编码失败: status={}", status);
                return null;
            }

            JsonNode geocodes = root.get("geocodes");
            if (geocodes == null || !geocodes.isArray() || geocodes.isEmpty()) {
                log.warn("[OpenMeteo] 未找到城市: {}", city);
                return null;
            }

            JsonNode location = geocodes.get(0).get("location");
            if (location == null || location.asText().isEmpty()) {
                return null;
            }

            String[] parts = location.asText().split(",");
            if (parts.length < 2) {
                return null;
            }

            return new double[]{Double.parseDouble(parts[1]), Double.parseDouble(parts[0])};
        }
    }

    /**
     * 调用 Open-Meteo 获取天气预报
     */
    private String getForecast(double latitude, double longitude, String city) throws Exception {
        // 构建 Open-Meteo API URL
        String dailyParams = "temperature_2m_max,temperature_2m_min,weathercode,temperature_2m_mean,windspeed_10m_max,precipitation_sum";
        String url = String.format(
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&daily=%s&timezone=auto&forecast_days=7",
                latitude, longitude, dailyParams
        );

        log.info("[OpenMeteo] 请求URL: {}", url);

        Request request = new Request.Builder().url(url).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return "❌ Open-Meteo API 请求失败，HTTP状态码：" + response.code();
            }

            String body = response.body().string();
            JsonNode root = objectMapper.readTree(body);

            JsonNode daily = root.get("daily");
            if (daily == null) {
                return "❌ 未获取到天气预报数据";
            }

            return formatResult(daily, city);
        }
    }

    /**
     * 格式化天气预报结果
     */
    private String formatResult(JsonNode daily, String city) {
        StringBuilder sb = new StringBuilder();
        sb.append("🌤️ ").append(city).append(" 天气预报（Open-Meteo）：\n\n");

        JsonNode times = daily.get("time");
        JsonNode maxTemps = daily.get("temperature_2m_max");
        JsonNode minTemps = daily.get("temperature_2m_min");
        JsonNode weatherCodes = daily.get("weathercode");
        JsonNode winds = daily.get("windspeed_10m_max");
        JsonNode precipitation = daily.get("precipitation_sum");

        if (times == null || !times.isArray()) {
            return "❌ 解析天气数据失败";
        }

        String[] weekDays = {"今天", "明天", "后天", "大后天", "第5天", "第6天", "第7天"};
        DateTimeFormatter inputFormatter = DateTimeFormatter.ISO_LOCAL_DATE;
        DateTimeFormatter outputFormatter = DateTimeFormatter.ofPattern("MM月dd日");

        int days = Math.min(times.size(), 7);
        for (int i = 0; i < days; i++) {
            String date = times.get(i).asText();
            double maxTemp = maxTemps != null && maxTemps.get(i) != null ? maxTemps.get(i).asDouble() : 0;
            double minTemp = minTemps != null && minTemps.get(i) != null ? minTemps.get(i).asDouble() : 0;
            int weatherCode = weatherCodes != null && weatherCodes.get(i) != null ? weatherCodes.get(i).asInt() : 0;
            double wind = winds != null && winds.get(i) != null ? winds.get(i).asDouble() : 0;
            double precip = precipitation != null && precipitation.get(i) != null ? precipitation.get(i).asDouble() : 0;

            // 格式化日期
            String dateStr;
            try {
                LocalDate localDate = LocalDate.parse(date, inputFormatter);
                dateStr = localDate.format(outputFormatter);
            } catch (Exception e) {
                dateStr = date;
            }

            // WMO天气代码转文字
            String weather = weatherCodeToText(weatherCode);
            String dayLabel = i < weekDays.length ? weekDays[i] : "第" + (i + 1) + "天";

            sb.append("【").append(dayLabel).append(" ").append(dateStr).append("】\n");
            sb.append("   ☀️ 天气：").append(weather).append("\n");
            sb.append("   🌡️ 温度：").append((int) minTemp).append("°C ~ ").append((int) maxTemp).append("°C\n");
            if (precip > 0) {
                sb.append("   🌧️ 降水：").append(String.format("%.1f", precip)).append("mm\n");
            }
            if (wind > 0) {
                sb.append("   💨 风速：").append((int) wind).append(" km/h\n");
            }
            sb.append("\n");
        }

        sb.append("💡 行程规划建议：请根据天气预报合理安排室内/室外活动。\n");
        sb.append("📍 数据来源：Open-Meteo（免费开源天气API）");
        return sb.toString();
    }

    /**
     * WMO天气代码转文字描述
     * 参考: https://open-meteo.com/en/docs
     */
    private String weatherCodeToText(int code) {
        if (code == 0) return "☀️ 晴朗";
        if (code == 1) return "🌤️ 基本晴朗";
        if (code == 2) return "⛅ 多云";
        if (code == 3) return "☁️ 阴天";
        if (code >= 45 && code <= 48) return "🌫️ 雾";
        if (code >= 51 && code <= 55) return "🌦️ 毛毛雨";
        if (code >= 56 && code <= 57) return "🌨️ 冻毛毛雨";
        if (code >= 61 && code <= 65) return "🌧️ 小雨到中雨";
        if (code >= 66 && code <= 67) return "🌨️ 冻雨";
        if (code >= 71 && code <= 77) return "❄️ 小雪到中雪";
        if (code == 80) return "🌦️ 阵雨";
        if (code == 81) return "🌧️ 中阵雨";
        if (code == 82) return "⛈️ 强阵雨";
        if (code >= 85 && code <= 86) return "❄️ 阵雪";
        if (code == 95) return "⛈️ 雷暴";
        if (code >= 96 && code <= 99) return "⛈️ 雷暴伴冰雹";
        return "❓ 未知";
    }
}
