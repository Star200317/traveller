package com.travel.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travel.entity.Place;
import com.travel.mapper.PlaceMapper;
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
 * 工具：查询地点库
 * 用于在生成旅游计划前，从本地地点库查询酒店、景点、餐厅信息
 * 确保推荐数据来源于已验证的地点库，而非 AI 随意编造
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchPlaceTool {

    private final PlaceMapper placeMapper;

    @Tool(description = """
            从本地地点库查询景点、酒店、餐厅信息。
            必须在生成旅游计划之前调用此工具获取地点数据！
            
            调用时机：用户提出旅游目的地需求后，生成行程计划之前。
            
            参数说明：
            - city: 目标城市（必填），如"厦门"、"大理"
            - type: 地点类型（必填），可选值：
              * attraction - 景点
              * hotel - 酒店
              * restaurant - 餐厅/美食
            
            返回格式：JSON数组，包含地点名称、详细地址、坐标、价格等信息
            如果查询结果为空，说明本地地点库暂无该类型数据，请联网搜索补充。
            """)
    public String searchPlaces(
            @ToolParam(description = "目标城市，如'厦门'") String city,
            @ToolParam(description = "地点类型：attraction=景点, hotel=酒店, restaurant=餐厅") String type) {

        if (city == null || city.trim().isEmpty()) {
            return "❌ 城市参数不能为空！请提供目标城市名称。";
        }

        if (type == null || type.trim().isEmpty()) {
            return "❌ 类型参数不能为空！请指定：attraction(景点)、hotel(酒店)、restaurant(餐厅)";
        }

        // 查询 place 表
        LambdaQueryWrapper<Place> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(Place::getCity, city.trim())
               .eq(Place::getType, type.trim().toLowerCase())
               .eq(Place::getDeleted, 0)
               .orderByDesc(Place::getCreateTime);

        List<Place> places = placeMapper.selectList(wrapper);

        if (places == null || places.isEmpty()) {
            log.info("[SearchPlace] 本地地点库无数据: city={}, type={}", city, type);
            return String.format("本地地点库中暂无 %s 的 %s 数据。请使用联网搜索工具获取信息。",
                    city,
                    "attraction".equals(type) ? "景点" : "hotel".equals(type) ? "酒店" : "餐厅");
        }

        // 转换为 JSON 格式返回
        List<Map<String, Object>> result = new ArrayList<>();
        for (Place p : places) {
            Map<String, Object> item = new HashMap<>();
            item.put("name", p.getName());
            item.put("address", p.getAddress());
            item.put("description", p.getDescription());
            item.put("price", p.getPrice());
            item.put("phone", p.getPhone());
            if (p.getLatitude() != null) {
                item.put("latitude", p.getLatitude().doubleValue());
            }
            if (p.getLongitude() != null) {
                item.put("longitude", p.getLongitude().doubleValue());
            }
            item.put("source", p.getSource());
            result.add(item);
        }

        log.info("[SearchPlace] 查询成功: city={}, type={}, count={}", city, type, result.size());

        // 构建可读返回
        StringBuilder sb = new StringBuilder();
        sb.append("✅ 本地地点库查询结果（共").append(result.size()).append("条）：\n\n");

        String typeName = "attraction".equals(type) ? "景点" : "hotel".equals(type) ? "酒店" : "餐厅";
        sb.append("📍 ").append(city).append(" ").append(typeName).append("列表：\n\n");

        for (int i = 0; i < result.size(); i++) {
            Map<String, Object> p = result.get(i);
            sb.append("【").append(i + 1).append("】").append(p.get("name")).append("\n");
            if (p.get("address") != null) {
                sb.append("   地址：").append(p.get("address")).append("\n");
            }
            if (p.get("price") != null && !p.get("price").toString().isEmpty()) {
                String priceUnit = "hotel".equals(type) ? "晚" : "人";
                sb.append("   价格：").append(p.get("price")).append("元/").append(priceUnit).append("\n");
            }
            if (p.get("description") != null && !p.get("description").toString().isEmpty()) {
                sb.append("   简介：").append(p.get("description")).append("\n");
            }
            if (p.get("latitude") != null && p.get("longitude") != null) {
                sb.append("   坐标：").append(p.get("latitude")).append(",").append(p.get("longitude")).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
