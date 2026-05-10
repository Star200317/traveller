package com.travel.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.entity.TravelPlan;
import com.travel.service.AmapService;
import com.travel.service.TravelPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具：保存旅游计划到数据库
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SavePlanTool {

    private final TravelPlanService travelPlanService;
    private final AmapService amapService;

    @Tool(description = "当用户确认旅游计划后，将计划保存到数据库。返回值为计划ID（长数字字符串，如'2045786267961270273'），必须原样用于后续的map_plan和pdf_export工具调用")
    public String savePlan(
            @ToolParam(description = "当前用户ID") Long userId,
            @ToolParam(description = "当前会话ID") Long conversationId,
            @ToolParam(description = "计划标题，如'西安5日游'") String title,
            @ToolParam(description = "目的地城市") String destination,
            @ToolParam(description = "出行天数") Integer days,
            @ToolParam(description = "出行人数，默认1") Integer peopleCount,
            @ToolParam(description = "预算金额（元），可选，支持数字或带'元'字的字符串") String budget,
            @ToolParam(description = "计划详情（JSON数组格式，包含每日行程安排、餐食、酒店）。格式：[{day:1, activities:[{name,time,ticket,description,address,latitude,longitude}], meals:[{type,time,name,address,price,recommendation,latitude,longitude}], hotel:{name,price,address,latitude,longitude}}]") String planContentJson) {
        try {
            // 容错解析 JSON，处理 AI 生成的各种畸形 JSON
            List<Map<String, Object>> dayList = parsePlanJson(planContentJson);

            // 逆地理编码补全缺失地址（坐标有但地址为空）
            enrichAddresses(dayList);

            // 【核心修复】无条件强制重查所有坐标 → 高德API获取门牌号级精确坐标
            // 必须在 validatePlanStructure 之前调用，确保保存到DB的是真实坐标
            forceRegeocodeAllCoordinates(dayList, destination);

            // 宽松校验：仅记录警告，不阻止保存（用户要求：只要能展示地图就行）
            String validationError = validatePlanStructure(dayList);
            if (validationError != null) {
                log.warn("[SavePlan] 数据结构 warning（不阻止保存）: {}", validationError);
            }

            TravelPlan plan = new TravelPlan();
            plan.setUserId(userId);
            plan.setConversationId(conversationId);
            plan.setTitle(title);
            plan.setDestination(destination);
            plan.setDays(days);
            plan.setPeopleCount(peopleCount != null ? peopleCount : 1);
            // 容错解析预算金额：支持 "1000"、"1000元"、"约2000" 等格式
            if (budget != null && !budget.trim().isEmpty()) {
                try {
                    // 提取字符串中的第一个数字（支持小数）
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("[0-9]+\\.?[0-9]*").matcher(budget);
                    if (m.find()) {
                        plan.setBudget(new BigDecimal(m.group()));
                    } else {
                        plan.setBudget(null);
                    }
                } catch (Exception e) {
                    log.warn("[SavePlan] 预算解析失败，忽略: budget={}, err={}", budget, e.getMessage());
                    plan.setBudget(null);
                }
            } else {
                plan.setBudget(null);
            }
            
            Map<String, Object> contentMap = new HashMap<>();
            contentMap.put("days", dayList);
            plan.setPlanContent(contentMap);
            plan.setStatus(1); // 草稿状态
            
            travelPlanService.save(plan);

            // 保存后立即生成 mapData（地理编码 + 路线规划），存入数据库
            try {
                Map<String, Object> mapData = travelPlanService.buildMapData(contentMap);
                travelPlanService.updateMapData(plan.getId(), mapData);
                log.info("[SavePlan] mapData 已生成并保存: planId={}, markers={}, polylines={}",
                        plan.getId(),
                        ((List<?>) mapData.getOrDefault("markers", new ArrayList<>())).size(),
                        ((List<?>) mapData.getOrDefault("polylines", new ArrayList<>())).size());
            } catch (Exception e) {
                log.warn("[SavePlan] mapData 生成失败（不影响计划保存）: {}", e.getMessage());
            }

            log.info("[SavePlan] 计划已保存: planId={}, title={}, days={}", plan.getId(), title, dayList.size());
            
            // 强制要求AI按正确顺序执行：展示表格 → 展示地图链接 → 询问满意度
            return "✅ 全部行程已一次性保存成功！计划ID：" + plan.getId() + "\n\n" +
                   "📌 【下一步 - 严格按顺序执行】\n\n" +
                   "1️⃣ 【立即】调用 map_plan(planId=" + plan.getId() + ") 生成地图数据\n" +
                   "2️⃣ 等待 map_plan 返回成功后，展示完整行程表格（全部天）\n" +
                   "3️⃣ 展示地图链接：🗺️ [点击查看完整地图](http://localhost:5173/map/" + plan.getId() + ")\n" +
                   "4️⃣ 询问用户满意度\n\n" +
                   "❌ 绝对禁止：不能再次调用 save_plan（已被创建且全部行程已保存！）\n\n" +
                   "正确格式（必须照抄，只用表格，禁止任何标签）：\n" +
                   "📅 第1天行程\n" +
                   "| 时间 | 景点/店名 | 花费 | 地址 | 简介 |\n" +
                   "|------|----------|------|------|------|\n" +
                   "| 07:30-08:00 | 🍳 店名 | 25元/人 | 地址 | 简介 |\n" +
                   "| 08:30-10:30 | 🏛️ 景点名 | 50元 | 地址 | 简介 |\n\n" +
                   "📅 第2天行程\n" +
                   "| 时间 | 景点/店名 | 花费 | 地址 | 简介 |\n" +
                   "|------|----------|------|------|------|\n" +
                   "...\n\n" +
                   "❌ 绝对禁止：🏨住宿标签、📍景点标签、•列表、段落描述\n" +
                   "planId=" + plan.getId();
        } catch (Exception e) {
            log.error("[SavePlan] 保存失败", e);
            return "保存失败：" + e.getMessage();
        }
    }

    /**
     * 增量保存单天计划（已废弃，改为一次性保存全部行程）
     * 保留此方法仅为兼容旧数据，AI不再调用
     */
    @SuppressWarnings("unchecked")
    public String updatePlanDay(  // @Tool注解已移除，AI看不到此方法
            @ToolParam(description = "已存在计划的ID") Long planId,
            @ToolParam(description = "第几天（从1开始）") Integer dayIndex,
            @ToolParam(description = "当天计划JSON，格式：{\"day\":1,\"activities\":[...],\"meals\":[...],\"hotel\":{...}}") String dayPlanJson) {
        try {
            // 容错解析当天计划JSON
            List<Map<String, Object>> dayList = parsePlanJson(dayPlanJson);
            if (dayList == null || dayList.isEmpty()) {
                return "更新失败：当天计划JSON格式错误或为空";
            }
            Map<String, Object> dayData = dayList.get(0);

            // 补全地址和坐标
            enrichAddresses(dayList);
            enrichCoordinates(dayList);

            // 宽松校验：仅记录警告，不阻止保存（用户要求：只要能展示地图就行）
            String validationError = validateDayStructure(dayData, dayIndex);
            if (validationError != null) {
                log.warn("[UpdatePlanDay] Day{} 数据结构 warning（不阻止保存）: {}", dayIndex, validationError);
            }

            // 查询现有计划
            TravelPlan plan = travelPlanService.getById(planId);
            if (plan == null) {
                return "更新失败：计划不存在，planId=" + planId;
            }

            Map<String, Object> contentMap = plan.getPlanContent();
            if (contentMap == null) {
                contentMap = new HashMap<>();
                contentMap.put("days", new java.util.ArrayList<Map<String, Object>>());
            }

            // 获取或初始化days列表
            List<Map<String, Object>> existingDays = (List<Map<String, Object>>) contentMap.getOrDefault("days", new java.util.ArrayList<>());
            if (existingDays == null) {
                existingDays = new java.util.ArrayList<>();
            }

            // 确保列表有足够的空间
            while (existingDays.size() < dayIndex) {
                existingDays.add(null);
            }

            // 替换或设置指定天的数据
            existingDays.set(dayIndex - 1, dayData);
            contentMap.put("days", existingDays);
            plan.setPlanContent(contentMap);
            travelPlanService.updateById(plan);

            // 更新后重新生成 mapData（地理编码 + 路线规划），存入数据库
            try {
                Map<String, Object> mapData = travelPlanService.buildMapData(contentMap);
                travelPlanService.updateMapData(planId, mapData);
                log.info("[UpdatePlanDay] mapData 已重新生成: planId={}, dayIndex={}", planId, dayIndex);
            } catch (Exception e) {
                log.warn("[UpdatePlanDay] mapData 重新生成失败（不影响计划保存）: {}", e.getMessage());
            }

            log.info("[UpdatePlanDay] Day{} 更新成功: planId={}", dayIndex, planId);

            return "✅ 第" + dayIndex + "天计划已保存！planId=" + planId + "\n" +
                   "如果还有更多天要添加，继续调用 updatePlanDay（使用相同planId）\n" +
                   "所有天都保存完后，调用 map_plan 生成地图";
        } catch (Exception e) {
            log.error("[UpdatePlanDay] 更新失败", e);
            return "更新失败：" + e.getMessage();
        }
    }

    /**
     * 校验单天数据结构（用于updatePlanDay）
     */
    @SuppressWarnings("unchecked")
    private String validateDayStructure(Map<String, Object> day, int dayNum) {
        if (day == null) {
            log.warn("[validateDay] 第{}天数据为null", dayNum);
            return null; // 不阻止保存，只记录警告
        }

        List<Map<String, Object>> activities = (List<Map<String, Object>>) day.get("activities");
        if (activities == null || activities.isEmpty()) {
            log.warn("[validateDay] 第{}天没有安排任何景点", dayNum);
        }

        if (activities != null) {
            for (int j = 0; j < activities.size(); j++) {
                Map<String, Object> act = activities.get(j);
                String[] requiredFields = {"name", "time", "address"};
                for (String field : requiredFields) {
                    if (!act.containsKey(field) || act.get(field) == null) {
                        log.warn("[validateDay] 第{}天第{}个景点缺少字段：{}", dayNum, j + 1, field);
                    }
                }
            }
        }

        List<Map<String, Object>> meals = (List<Map<String, Object>>) day.get("meals");
        if (meals == null || meals.isEmpty()) {
            log.warn("[validateDay] 第{}天缺少餐食安排（允许跳过）", dayNum);
        }

        // 不再强制要求早中晚三餐齐全，只要有餐食信息即可
        Map<String, Object> hotel = (Map<String, Object>) day.get("hotel");
        if (hotel == null) {
            log.warn("[validateDay] 第{}天缺少酒店信息（允许跳过）", dayNum);
        }

        return null; // 永远返回null，不阻止保存
    }

    /**
     * 校验计划数据结构（宽松模式，仅记录警告，不阻止保存）
     * @return 永远返回null（不阻止保存）
     */
    private String validatePlanStructure(List<Map<String, Object>> dayList) {
        if (dayList == null || dayList.isEmpty()) {
            log.warn("[validatePlan] 计划天数为空（允许保存）");
            return null;
        }

        for (int i = 0; i < dayList.size(); i++) {
            Map<String, Object> day = dayList.get(i);
            int dayNum = i + 1;

            // 宽松校验activities（只记录警告）
            List<Map<String, Object>> activities = (List<Map<String, Object>>) day.get("activities");
            if (activities == null || activities.isEmpty()) {
                log.warn("[validatePlan] 第{}天没有安排任何景点（允许保存）", dayNum);
                continue;
            }

            // 不再强制要求全部字段齐全，只要有name和address即可
            for (int j = 0; j < activities.size(); j++) {
                Map<String, Object> act = activities.get(j);
                if (!act.containsKey("name") || act.get("name") == null) {
                    log.warn("[validatePlan] 第{}天第{}个景点缺少name字段（允许保存）", dayNum, j + 1);
                }
            }

            // 宽松校验meals（不再强制三餐齐全）
            List<Map<String, Object>> meals = (List<Map<String, Object>>) day.get("meals");
            if (meals == null || meals.isEmpty()) {
                log.warn("[validatePlan] 第{}天缺少餐食安排（允许保存）", dayNum);
            }

            // 宽松校验hotel（不再强制要求）
            Map<String, Object> hotel = (Map<String, Object>) day.get("hotel");
            if (hotel == null) {
                log.warn("[validatePlan] 第{}天缺少酒店信息（允许保存）", dayNum);
            }
        }

        return null; // 永远返回null，不阻止保存
    }

    /**
     * 遍历所有景点/餐食/酒店，有坐标无地址时调用高德逆地理编码补全
     */
    @SuppressWarnings("unchecked")
    private void enrichAddresses(List<Map<String, Object>> dayList) {
        for (Map<String, Object> day : dayList) {
            // 补全景点地址
            List<Map<String, Object>> activities = (List<Map<String, Object>>) day.getOrDefault("activities", List.of());
            for (Map<String, Object> act : activities) {
                enrichSingleAddress(act, (String) act.get("name"), "景点");
            }

            // 补全餐食地址
            List<Map<String, Object>> meals = (List<Map<String, Object>>) day.getOrDefault("meals", List.of());
            for (Map<String, Object> meal : meals) {
                enrichSingleAddress(meal, (String) meal.get("name"), "餐食");
            }

            // 补全酒店地址
            Map<String, Object> hotel = (Map<String, Object>) day.get("hotel");
            if (hotel != null) {
                enrichSingleAddress(hotel, (String) hotel.get("name"), "酒店");
            }
        }
    }

    /**
     * 遍历所有景点/餐食/酒店，有地址无坐标时调用高德地理编码补全
     */
    @SuppressWarnings("unchecked")
    private void enrichCoordinates(List<Map<String, Object>> dayList) {
        for (Map<String, Object> day : dayList) {
            // 补全景点坐标
            List<Map<String, Object>> activities = (List<Map<String, Object>>) day.getOrDefault("activities", List.of());
            for (Map<String, Object> act : activities) {
                enrichSingleCoordinate(act, "景点");
            }

            // 补全餐食坐标
            List<Map<String, Object>> meals = (List<Map<String, Object>>) day.getOrDefault("meals", List.of());
            for (Map<String, Object> meal : meals) {
                enrichSingleCoordinate(meal, "餐食");
            }

            // 补全酒店坐标
            Map<String, Object> hotel = (Map<String, Object>) day.get("hotel");
            if (hotel != null) {
                enrichSingleCoordinate(hotel, "酒店");
            }
        }
    }

    private void enrichSingleAddress(Map<String, Object> item, String name, String type) {
        String addr = (String) item.getOrDefault("address", "");
        if (addr == null) addr = "";
        if (!addr.trim().isEmpty()) return; // 地址已有，不处理

        Object lat = item.get("latitude");
        Object lng = item.get("longitude");
        if (lat == null || lng == null) return; // 坐标也没有，不处理

        double latVal = toDouble(lat);
        double lngVal = toDouble(lng);
        if (latVal == 0 && lngVal == 0) return;

        String geocoded = amapService.regeocode(lngVal, latVal);
        if (geocoded != null && !geocoded.trim().isEmpty()) {
            item.put("address", geocoded);
            log.info("[SavePlan] {}「{}」逆地理编码补全地址: {}", type, name, geocoded);
        }
    }

    /**
     * 对单个地点补全坐标（地址有但坐标为空或无效时，调用高德地理编码）
     */
    private void enrichSingleCoordinate(Map<String, Object> item, String type) {
        String addr = (String) item.getOrDefault("address", "");
        if (addr == null || addr.trim().isEmpty()) return; // 地址也没有，不处理

        Object lat = item.get("latitude");
        Object lng = item.get("longitude");
        double latVal = toDouble(lat);
        double lngVal = toDouble(lng);

        // 坐标已有且有效，不处理
        if (latVal != 0.0 && lngVal != 0.0) return;

        // 调用高德地理编码：地址 → 坐标
        double[] coords = amapService.geocode(addr, null);
        if (coords != null && coords.length >= 2 && coords[0] != 0.0 && coords[1] != 0.0) {
            item.put("longitude", coords[0]);
            item.put("latitude", coords[1]);
            log.info("[SavePlan] {}「{}」地理编码补全坐标: {}, {}", type, item.get("name"), coords[0], coords[1]);
        } else {
            log.warn("[SavePlan] {}「{}」地理编码失败，地址: {}", type, item.get("name"), addr);
        }
    }

    /**
     * 【2026-05-10核心修复】无条件强制重查所有地点坐标
     *
     * 设计原理：
     * 1. AI（LLM）天生不擅长生成精确经纬度，经多次验证会编造假坐标
     *    - 典型表现：所有景点坐标都集中在同一区域（如24.45,118.08）
     *    - 或有微小浮动(±0.01)但仍然不是真实位置
     * 2. 原enrichCoordinates只在坐标为空/0时才查询→AI给了假坐标就跳过
     * 3. 原detectAndFixDuplicateCoordinates用严格阈值(0.001°)检测→AI浮动后检测不到
     *
     * 新策略：
     * - 无视AI提供的任何坐标值（不管是否有值、是否看起来"合理"）
     * - 对所有有address的地点，统一调用高德地理编码API获取真实坐标
     * - 高德API可返回门牌号级别精度（如"思明区中山路22号" → 精确经纬度）
     * - 查询关键词优化：使用 "城市+地址/名称" 格式提高匹配精度
     *
     * @param dayList      行程天数列表
     * @param destination  目的地城市（用于地理编码时提高精度）
     */
    @SuppressWarnings("unchecked")
    private void forceRegeocodeAllCoordinates(List<Map<String, Object>> dayList, String destination) {
        int totalCount = 0;
        int successCount = 0;
        int failCount = 0;

        for (int d = 0; d < dayList.size(); d++) {
            Map<String, Object> day = dayList.get(d);
            int dayNum = d + 1;

            // 处理景点
            List<Map<String, Object>> activities = (List<Map<String, Object>>) day.getOrDefault("activities", List.of());
            for (Map<String, Object> act : activities) {
                totalCount++;
                if (forceRecodeSingle(act, "景点", destination, dayNum)) successCount++;
                else failCount++;
            }

            // 处理餐食
            List<Map<String, Object>> meals = (List<Map<String, Object>>) day.getOrDefault("meals", List.of());
            for (Map<String, Object> meal : meals) {
                totalCount++;
                if (forceRecodeSingle(meal, "餐食", destination, dayNum)) successCount++;
                else failCount++;
            }

            // 处理酒店
            Map<String, Object> hotel = (Map<String, Object>) day.get("hotel");
            if (hotel != null) {
                totalCount++;
                if (forceRecodeSingle(hotel, "酒店", destination, dayNum)) successCount++;
                else failCount++;
            }
        }

        log.info("[SavePlan] forceRegeocodeAllCoordinates 完成: 总计{}个地点, 成功{}个, 失败{}个 (城市={})",
                totalCount, successCount, failCount, destination);
    }

    /**
     * 对单个地点执行强制地理编码
     *
     * @return true=成功获取到真实坐标并覆盖, false=跳过或失败
     */
    @SuppressWarnings("unchecked")
    private boolean forceRecodeSingle(Map<String, Object> item, String type, String city, int dayNum) {
        String name = (String) item.get("name");
        String addr = (String) item.getOrDefault("address", "");

        double oldLat = toDouble(item.get("latitude"));
        double oldLng = toDouble(item.get("longitude"));

        // 场景1：没有地址 → 尝试用名称搜索
        if (addr == null || addr.trim().isEmpty()) {
            if (name != null && !name.trim().isEmpty() && city != null && !city.trim().isEmpty()) {
                // 用 "城市+名称" 搜索
                String searchKey = city + name;
                double[] coords = amapService.geocode(searchKey, city);
                if (coords != null && coords.length >= 2 && coords[0] != 0.0 && coords[1] != 0.0) {
                    item.put("longitude", coords[0]);
                    item.put("latitude", coords[1]);
                    log.info("[SavePlan] Day{} {}「{}」名称搜索成功: ({},{}) → ({},{}) [无地址]",
                            dayNum, type, name, oldLat, oldLng, coords[1], coords[0]);
                    return true;
                }
            }
            log.warn("[SavePlan] Day{} {}「{}」跳过: 无地址且名称搜索失败", dayNum, type, name);
            return false;
        }

        // 场景2：有地址 → 用 "城市+详细地址" 查询（最高精度，可达门牌号级别）
        // 高德地理编码API支持格式："福建省厦门市思明区中山路22号"
        String searchAddr = addr;
        if (city != null && !city.trim().isEmpty() && !addr.contains(city)) {
            searchAddr = city + addr;
        }

        double[] coords = amapService.geocode(searchAddr, city);
        if (coords != null && coords.length >= 2 && coords[0] != 0.0 && coords[1] != 0.0) {
            item.put("longitude", coords[0]);
            item.put("latitude", coords[1]);

            // 判断坐标是否真的被改变了（如果新旧坐标差异<0.0001度≈11米，视为未改变）
            double latDiff = Math.abs(coords[1] - oldLat);
            double lngDiff = Math.abs(coords[0] - oldLng);
            boolean changed = latDiff > 0.0001 || lngDiff > 0.0001;

            if (changed) {
                log.info("[SavePlan] Day{} {}「{}」坐标已修正: ({},{}) → ({},{}) [地址: {}]",
                        dayNum, type, name, oldLat, oldLng, coords[1], coords[0], addr);
            } else {
                log.info("[SavePlan] Day{} {}「{}」坐标确认一致: ({},{}) [地址: {}]",
                        dayNum, type, name, coords[1], coords[0], addr);
            }
            return true;
        }

        // 场景3：地址查询失败 → 降级尝试仅用名称搜索
        if (name != null && !name.trim().isEmpty()) {
            String nameSearchKey = (city != null && !city.trim().isEmpty()) ? city + name : name;
            coords = amapService.geocode(nameSearchKey, city);
            if (coords != null && coords.length >= 2 && coords[0] != 0.0 && coords[1] != 0.0) {
                item.put("longitude", coords[0]);
                item.put("latitude", coords[1]);
                log.info("[SavePlan] Day{} {}「{}」降级名称搜索成功: ({},{}) → ({},{})",
                        dayNum, type, name, oldLat, oldLng, coords[1], coords[0]);
                return true;
            }
        }

        log.warn("[SavePlan] Day{} {}「{}」地理编码全部失败: address={}, 保留原坐标({},{})",
                dayNum, type, name, addr, oldLat, oldLng);
        return false;
    }

    /**
     * 【关键修复】检测并修复重复坐标问题（已降级为辅助角色，主逻辑由forceRegeocodeAllCoordinates承担）
     * 当 AI 给所有景点填入相同的假坐标（如全部 24.45,118.08）时，
     * 检测到后自动用每个地点的地址调用高德地理编码获取真实坐标。
     *
     * 触发条件：
     * - 所有景点的坐标完全相同（或超过80%相同）
     * - 且坐标不是 (0,0)（(0,0)的情况已被 enrichCoordinates 处理）
     */
    @SuppressWarnings("unchecked")
    private void detectAndFixDuplicateCoordinates(List<Map<String, Object>> dayList) {
        // 收集所有有效坐标
        List<double[]> allCoords = new ArrayList<>();
        int totalPoints = 0;
        int sameCoordCount = 0;

        for (Map<String, Object> day : dayList) {
            List<Map<String, Object>> activities = (List<Map<String, Object>>) day.getOrDefault("activities", List.of());
            for (Map<String, Object> act : activities) {
                double lat = toDouble(act.get("latitude"));
                double lng = toDouble(act.get("longitude"));
                if (lat != 0.0 || lng != 0.0) {
                    allCoords.add(new double[]{lat, lng});
                    totalPoints++;
                }
            }
            List<Map<String, Object>> meals = (List<Map<String, Object>>) day.getOrDefault("meals", List.of());
            for (Map<String, Object> meal : meals) {
                double lat = toDouble(meal.get("latitude"));
                double lng = toDouble(meal.get("longitude"));
                if (lat != 0.0 || lng != 0.0) {
                    allCoords.add(new double[]{lat, lng});
                    totalPoints++;
                }
            }
        }

        // 坐标点太少，不需要检测
        if (totalPoints < 3) return;

        // 检查是否所有坐标都相同
        double[] firstCoord = allCoords.get(0);
        for (double[] coord : allCoords) {
            if (Math.abs(coord[0] - firstCoord[0]) < 0.001 && Math.abs(coord[1] - firstCoord[1]) < 0.001) {
                sameCoordCount++;
            }
        }

        double duplicateRatio = (double) sameCoordCount / totalPoints;
        if (duplicateRatio < 0.8) return; // 不到80%重复，认为是正常数据

        // 检测到大量重复坐标！记录警告并强制重新地理编码
        log.warn("[SavePlan] ⚠️ 检测到坐标异常：{}/{} 个地点使用相同坐标 ({}, {})，疑似AI填入假坐标，开始强制重新地理编码...",
                sameCoordCount, totalPoints, firstCoord[1], firstCoord[0]);

        int fixedCount = 0;
        for (Map<String, Object> day : dayList) {
            // 修复景点坐标
            List<Map<String, Object>> activities = (List<Map<String, Object>>) day.getOrDefault("activities", List.of());
            for (Map<String, Object> act : activities) {
                fixedCount += forceReGeocode(act, "景点");
            }
            // 修复餐食坐标
            List<Map<String, Object>> meals = (List<Map<String, Object>>) day.getOrDefault("meals", List.of());
            for (Map<String, Object> meal : meals) {
                fixedCount += forceReGeocode(meal, "餐食");
            }
        }

        log.info("[SavePlan] 强制重新地理编码完成：修正了 {} 个地点的坐标", fixedCount);
    }

    /**
     * 强制对单个地点重新进行地理编码（不管当前坐标是什么值）
     */
    private int forceReGeocode(Map<String, Object> item, String type) {
        String name = (String) item.get("name");
        String addr = (String) item.getOrDefault("address", "");
        
        // 如果有地址，直接用地址做地理编码
        if (addr != null && !addr.trim().isEmpty()) {
            double[] coords = amapService.geocode(addr, name);
            if (coords != null && coords.length >= 2 && coords[0] != 0.0 && coords[1] != 0.0) {
                double oldLat = toDouble(item.get("latitude"));
                double oldLng = toDouble(item.get("longitude"));
                item.put("longitude", coords[0]);
                item.put("latitude", coords[1]);
                log.info("[SavePlan] ✅ {}「{}」坐标修正: ({},{}) → ({},{})",
                        type, name, oldLat, oldLng, coords[1], coords[0]);
                return 1;
            } else {
                // 地址地理编码失败，尝试用名称搜索
                log.warn("[SavePlan] {}「{}」地址地理编码失败，尝试用名称: {}", type, name, addr);
            }
        }
        
        // 如果地址为空或地址编码失败，尝试用"城市+名称"格式搜索
        // 从同一天的 plan 中获取目的地信息
        return 0;
    }

    /**
     * 安全转换为 double
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

    /**
     * 容错 JSON 解析：处理 AI 生成的畸形 JSON
     * 支持被截断的 JSON 自动补全
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parsePlanJson(String json) throws Exception {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("计划JSON不能为空");
        }

        // 先尝试标准解析
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            Object parsed = mapper.readValue(json, Object.class);
            if (parsed instanceof List) {
                return (List<Map<String, Object>>) parsed;
            }
            // 如果是单个对象，自动包装成数组（AI 常传入单个对象而非数组）
            if (parsed instanceof Map) {
                log.info("[SavePlan] JSON是单个对象，自动包装成数组");
                List<Map<String, Object>> result = new java.util.ArrayList<>();
                result.add((Map<String, Object>) parsed);
                return result;
            }
            throw new IllegalArgumentException("计划JSON必须是数组或对象格式");
        } catch (JsonProcessingException e) {
            log.warn("[SavePlan] 标准JSON解析失败，尝试容错解析: {}", e.getMessage());
        }

        // 容错处理：修复 AI 生成的畸形 JSON
        String fixed = fixMalformedJson(json);
        
        // 关键：如果 JSON 被截断，尝试自动补全
        fixed = autoCompleteTruncatedJson(fixed);
        
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            Object parsed = mapper.readValue(fixed, Object.class);
            if (parsed instanceof List) {
                log.info("[SavePlan] 容错解析成功");
                return (List<Map<String, Object>>) parsed;
            }
            // 如果是单个对象，自动包装成数组
            if (parsed instanceof Map) {
                log.info("[SavePlan] 容错解析：JSON是单个对象，自动包装成数组");
                List<Map<String, Object>> result = new java.util.ArrayList<>();
                result.add((Map<String, Object>) parsed);
                return result;
            }
            throw new IllegalArgumentException("计划JSON必须是数组或对象格式");
        } catch (JsonProcessingException e) {
            log.error("[SavePlan] 容错解析也失败，fixed JSON 前300字符: {}", 
                fixed.substring(0, Math.min(300, fixed.length())));
            throw new Exception("JSON格式错误，无法解析：" + e.getMessage() 
                + "。建议：请确保SYSTEM_PROMPT中要求AI分天调用save_plan/updatePlanDay，避免一次性输出完整计划。");
        }
    }

    /**
     * 自动补全被截断的 JSON（AI 输出 token 超限时常见）
     * 例如：json 以 [{...},{...}, 结尾（缺少关闭的 ]），尝试补全
     */
    private String autoCompleteTruncatedJson(String json) {
        if (json == null || json.trim().isEmpty()) return json;
        
        String s = json.trim();
        
        // 统计未闭合的括号/方括号
        int openBraces = 0;
        int openBrackets = 0;
        boolean inString = false;
        char lastChar = 0;
        
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (lastChar == '\\') {
                lastChar = c;
                continue;
            }
            if (c == '"' && !inString) {
                inString = true;
            } else if (c == '"' && inString) {
                inString = false;
            } else if (!inString) {
                if (c == '{') openBraces++;
                else if (c == '}') openBraces--;
                else if (c == '[') openBrackets++;
                else if (c == ']') openBrackets--;
            }
            lastChar = c;
        }
        
        StringBuilder sb = new StringBuilder(s);
        
        // 如果字符串未闭合，先闭合字符串
        if (inString) {
            sb.append("\"");
        }
        
        // 补全未闭合的 }
        for (int i = 0; i < openBraces; i++) {
            sb.append("}");
        }
        
        // 补全未闭合的 ]
        for (int i = 0; i < openBrackets; i++) {
            sb.append("]");
        }
        
        String result = sb.toString();
        if (!result.equals(s)) {
            log.info("[SavePlan] 自动补全截断JSON: 补全了 {} 个}} 和 {} 个]", openBraces, openBrackets);
        }
        
        return result;
    }

    private String fixMalformedJson(String json) {
        String fixed = json;

        // 第0遍：专门修复"只有闭引号、缺少开引号"的 key（如 {day":1} → {"day":1}）
        // 用循环逐字符处理，在 "word": 模式的 word 前加开引号
        fixed = fixMissingOpenQuotes(fixed);

        // 第一遍：修复 Python dict 风格（key=value 或 key: value 无引号）
        fixed = convertPythonDictToJson(fixed);

        // 第二遍：修复中文引号
        fixed = fixed.replaceAll("[\u201c\u201d]", "\"");
        fixed = fixed.replaceAll("[\u2018\u2019]", "'");

        // 第三遍：为没有引号的字段名加引号
        fixed = addQuotesToUnquotedKeys(fixed);

        // 第四遍：修复字符串值内未转义的双引号
        fixed = fixUnescapedQuotesInStringValues(fixed);

        // 第五遍：移除尾部逗号
        fixed = fixed.replaceAll(",\\s*([\\]\\}])", "$1");

        // 第六遍：为字符串值加引号（如果值没有引号）
        fixed = addQuotesToStringValues(fixed);

        return fixed;
    }

    /**
     * 修复"只有闭引号、缺少开引号"的 key
     * 例如：{day":1, activities":[...]} → {"day":1, "activities":[...]}
     * 匹配模式：在 " 前面是字母数字，且 " 后面是可选空格+冒号，则在 " 后插入开引号
     * 实际上错误格式是 key" : 值，需要在 key 前面插入 "
     */
    private String fixMissingOpenQuotes(String input) {
        // 匹配：{ 或 [ 或 , 或空格 后面跟着字母数字（key），再跟着 " :
        // 在 key 前面插入开引号 "
        // 正则说明：([{,[\\\s]+)([a-zA-Z0-9_]+)("[\s]*:)
        //   $1=前置字符 $2=key  $3=" :   → 替换后：$1"$2$3 → 即 $1"key" :
        //  但 $3 里有 "，需要把 " 移到 $2 前面
        //  正确做法：用 $1"$2$3 但 $3 中的 " 是闭引号，结果是 $1"key"$3 → 相当于 key 前后都有 "
        //  实际上：把 " 从 $3 移除，加到 $2 前后 →  ($1)(key)(" : ) → $1"$2"$3
        //  Java 里 $3 是 " : ，替换后 $1"$2" :   → 正确！
        //  但 Java replaceAll 的 $2 后面紧跟 " 会被解析为 $2" 这个组名，需要把 " 分开写
        //  技巧：用 $1\u0022$2\u0022$3 或者用字符串拼接思路
        //  更简单的写法：用一个中间替换
        String step1 = input.replaceAll("([\\{,\\[]\\s+)([A-Za-z0-9_]+)(\"\\s*:)", "$1\"$2$3");
        // 上面一行还有问题：$2" 会被 Java 解释成 $2 后面跟着 "
        // 正确写法：用 $1$2$3 然后单独处理
        // 放弃正则，用循环实现
        return fixMissingOpenQuotesByLoop(input);
    }

    private String fixMissingOpenQuotesByLoop(String input) {
        // 循环查找 "word": 模式（word 前没有 "），在 word 前插入 "
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int len = input.length();
        while (i < len) {
            // 从当前位置尝试匹配：前面是 { 或 [ 或 , 或空格或换行，后面是字母数字（key开始）
            if (input.charAt(i) == '{' || input.charAt(i) == '[' || input.charAt(i) == ','
                    || Character.isWhitespace(input.charAt(i))) {
                int keyStart = i + 1;
                while (keyStart < len && Character.isWhitespace(input.charAt(keyStart))) keyStart++;
                if (keyStart < len && (Character.isLetterOrDigit(input.charAt(keyStart))
                        || input.charAt(keyStart) == '_')) {
                    int keyEnd = keyStart;
                    while (keyEnd < len && (Character.isLetterOrDigit(input.charAt(keyEnd))
                            || input.charAt(keyEnd) == '_')) keyEnd++;
                    // keyEnd 现在是 " 的位置
                    if (keyEnd < len && input.charAt(keyEnd) == '"') {
                        // 检查 keyStart 前面是否已经有 "
                        boolean hasOpenQuote = (keyStart > 0 && input.charAt(keyStart - 1) == '"');
                        sb.append(input, i, keyStart); // 写入前置字符（不含key）
                        if (!hasOpenQuote) {
                            sb.append('"'); // 补上开引号
                        }
                        sb.append(input, keyStart, keyEnd + 1); // 写入 key + 闭引号
                        i = keyEnd + 1;
                        continue;
                    }
                }
            }
            sb.append(input.charAt(i));
            i++;
        }
        return sb.toString();
    }
    
    /**
     * 将 Python dict 风格转换为 JSON
     * 例如：{name=鼓浪屿, time=14:00} → {"name": "鼓浪屿", "time": "14:00"}
     */
    private String convertPythonDictToJson(String input) {
        if (input == null || input.isEmpty()) return input;
        
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int len = input.length();
        
        while (i < len) {
            char c = input.charAt(i);
            
            if (c == '{' || c == '[') {
                // 找到下一个匹配的闭合括号
                char closeChar = (c == '{') ? '}' : ']';
                sb.append(c);
                i++;
                
                // 处理对象/数组内容
                StringBuilder content = new StringBuilder();
                int depth = 1;
                boolean inQuote = false;
                while (i < len && depth > 0) {
                    char ch = input.charAt(i);
                    if (ch == '\\' && i + 1 < len && input.charAt(i + 1) == '"') {
                        content.append(ch).append(input.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (ch == '"' && !inQuote) {
                        inQuote = true;
                    } else if (ch == '"' && inQuote) {
                        // 检查前面是否有奇数个反斜杠
                        int backslashCount = 0;
                        for (int j = i - 1; j >= 0 && input.charAt(j) == '\\'; j--) {
                            backslashCount++;
                        }
                        if (backslashCount % 2 == 0) {
                            inQuote = false;
                        }
                    } else if (!inQuote) {
                        if (ch == '{' || ch == '[') depth++;
                        else if (ch == '}' || ch == ']') depth--;
                    }
                    content.append(ch);
                    i++;
                    if (depth == 0) break;
                }
                
                // 递归处理内容
                String processed = convertPythonDictToJson(content.toString());
                sb.append(processed);
                
            } else if (c == '=' && !isInsideString(input, i)) {
                // 将 = 替换为 :
                sb.append(':');
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        
        return sb.toString();
    }
    
    /**
     * 检查位置 i 是否在字符串内部
     */
    private boolean isInsideString(String s, int pos) {
        boolean inString = false;
        for (int i = 0; i < pos; i++) {
            if (s.charAt(i) == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
        }
        return inString;
    }
    
    /**
     * 为没有引号的字段名加引号
     * 例如：{name: 或 {name, → {"name":
     */
    private String addQuotesToUnquotedKeys(String json) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int len = json.length();
        
        while (i < len) {
            char c = json.charAt(i);
            
            if (c == '"') {
                // 跳过字符串
                sb.append(c);
                i++;
                while (i < len && json.charAt(i) != '"') {
                    sb.append(json.charAt(i));
                    if (json.charAt(i) == '\\') {
                        i++;
                        if (i < len) sb.append(json.charAt(i));
                    }
                    i++;
                }
                if (i < len) {
                    sb.append(json.charAt(i));
                    i++;
                }
            } else if (c == '{' || c == ',') {
                // 可能是新字段的开始
                sb.append(c);
                i++;
                
                // 跳过空白
                while (i < len && (json.charAt(i) == ' ' || json.charAt(i) == '\n' || json.charAt(i) == '\r' || json.charAt(i) == '\t')) {
                    sb.append(json.charAt(i));
                    i++;
                }
                
                // 检查下一个token是否是没有引号的字段名
                if (i < len && json.charAt(i) != '"' && json.charAt(i) != '}' && json.charAt(i) != ']') {
                    // 读取到 : 或 , 或 }
                    StringBuilder keyBuilder = new StringBuilder();
                    while (i < len && json.charAt(i) != ':' && json.charAt(i) != ',' && json.charAt(i) != '}' && json.charAt(i) != ']') {
                        keyBuilder.append(json.charAt(i));
                        i++;
                    }
                    
                    String key = keyBuilder.toString().trim();
                    if (!key.isEmpty() && !isNumeric(key)) {
                        sb.append('"').append(key).append('"');
                    } else {
                        sb.append(key);
                    }
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        
        return sb.toString();
    }
    
    /**
     * 为字符串值加引号（处理值为中文/英文但没有引号的情况）
     */
    private String addQuotesToStringValues(String json) {
        // 匹配 : 后面没有引号的字符串值
        // 简化处理：匹配 : 后跟中文或字母开头的值
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int len = json.length();
        
        while (i < len) {
            char c = json.charAt(i);
            sb.append(c);
            i++;
            
            if (c == ':') {
                // 跳过冒号后的空白
                while (i < len && (json.charAt(i) == ' ' || json.charAt(i) == '\n')) {
                    sb.append(json.charAt(i));
                    i++;
                }
                
                // 如果值不是 " [ { 数字 开头，且没有引号，则加引号
                if (i < len && json.charAt(i) != '"' && json.charAt(i) != '[' && json.charAt(i) != '{' 
                    && json.charAt(i) != 't' && json.charAt(i) != 'f' && json.charAt(i) != 'n'
                    && !Character.isDigit(json.charAt(i)) && json.charAt(i) != '-') {
                    
                    // 读取到 , 或 } 或 ] 
                    StringBuilder valBuilder = new StringBuilder();
                    int depth = 0;
                    while (i < len && !(depth == 0 && (json.charAt(i) == ',' || json.charAt(i) == '}' || json.charAt(i) == ']'))) {
                        if (json.charAt(i) == '{' || json.charAt(i) == '[') depth++;
                        else if (json.charAt(i) == '}' || json.charAt(i) == ']') depth--;
                        valBuilder.append(json.charAt(i));
                        i++;
                    }
                    
                    String val = valBuilder.toString().trim();
                    if (!val.isEmpty() && !isNumeric(val)) {
                        sb.append('"').append(val).append('"');
                    } else {
                        sb.append(val);
                    }
                }
            }
        }
        
        return sb.toString();
    }
    
    private boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String fixUnescapedQuotesInStringValues(String json) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        int len = json.length();

        while (i < len) {
            char c = json.charAt(i);
            if (c == '"') {
                // 找 key
                StringBuilder keyBuilder = new StringBuilder();
                i++;
                while (i < len && json.charAt(i) != ':') {
                    keyBuilder.append(json.charAt(i));
                    i++;
                }
                result.append(keyBuilder);
                if (i < len) result.append(':');
                i++;

                // 跳过空白
                while (i < len && (json.charAt(i) == ' ' || json.charAt(i) == '\n' || json.charAt(i) == '\r' || json.charAt(i) == '\t')) {
                    result.append(json.charAt(i));
                    i++;
                }
                if (i >= len) break;

                if (json.charAt(i) == '"') {
                    result.append('"');
                    i++;
                    boolean escaped = false;
                    while (i < len) {
                        char ch = json.charAt(i);
                        if (escaped) {
                            result.append(ch);
                            escaped = false;
                            i++;
                            continue;
                        }
                        if (ch == '\\') {
                            result.append(ch);
                            escaped = true;
                            i++;
                            continue;
                        }
                        if (ch == '"') {
                            result.append(ch);
                            i++;
                            break;
                        }
                        result.append(ch);
                        i++;
                    }
                } else {
                    result.append(json.charAt(i));
                    i++;
                }
            } else {
                result.append(c);
                i++;
            }
        }
        return result.toString();
    }
}
