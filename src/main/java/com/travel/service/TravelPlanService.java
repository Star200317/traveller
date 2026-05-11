package com.travel.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.travel.dto.PlanSaveDTO;
import com.travel.entity.PlanItem;
import com.travel.entity.TravelPlan;
import com.travel.mapper.PlanItemMapper;
import com.travel.mapper.TravelPlanMapper;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TravelPlanService extends ServiceImpl<TravelPlanMapper, TravelPlan> {

    private final PlanItemMapper planItemMapper;

    /**
     * 保存计划项（根据 items 中的 dayDate 字段确定所属日期）
     */
    @Transactional
    public void savePlanItemsAndDays(Long planId, List<Map<String, Object>> items) {
        // 1. 删除该计划的现有计划项
        planItemMapper.deleteByPlanId(planId);

        // 2. 插入新的计划项
        if (items != null) {
            int sortOrder = 0;
            for (Map<String, Object> itemMap : items) {
                PlanItem item = new PlanItem();
                item.setPlanId(planId);

                // placeId 可能为空（前端临时id），为空时设为null
                Object placeIdObj = itemMap.get("placeId");
                if (placeIdObj != null && !"".equals(placeIdObj.toString().trim())) {
                    try {
                        item.setPlaceId(Long.valueOf(placeIdObj.toString()));
                    } catch (NumberFormatException e) {
                        item.setPlaceId(null);
                    }
                }

                // 优先使用 dayDate（前端日期字符串），向后兼容 dayIndex
                Object dayDateObj = itemMap.get("dayDate");
                if (dayDateObj != null && !"".equals(dayDateObj.toString().trim())) {
                    item.setDayDate(dayDateObj.toString());
                } else {
                    // 向后兼容：旧数据可能只有 dayIndex
                    Object dayIndexObj = itemMap.get("dayIndex");
                    if (dayIndexObj != null) {
                        try {
                            int idx = Integer.valueOf(dayIndexObj.toString());
                            item.setDayDate("第" + (idx + 1) + "天");
                        } catch (NumberFormatException e) {
                            item.setDayDate(null);
                        }
                    } else {
                        item.setDayDate(null);
                    }
                }

                item.setSortOrder(sortOrder++);
                item.setNotes(itemMap.containsKey("notes") && itemMap.get("notes") != null
                        ? itemMap.get("notes").toString() : null);
                item.setDuration(itemMap.containsKey("duration") && itemMap.get("duration") != null
                        ? itemMap.get("duration").toString() : null);
                planItemMapper.insert(item);
            }
        }
    }

    /**
     * 保存计划及其地点项（合并新建/更新）
     * 根据 dto.id 是否为 null 判断是新建还是更新
     */
    @Transactional
    public TravelPlan savePlanWithItems(PlanSaveDTO dto) {
        TravelPlan plan;
        Long userId = StpUtil.getLoginIdAsLong();

        if (dto.getId() == null) {
            // 新建计划
            plan = new TravelPlan();
            plan.setUserId(userId);
            plan.setTitle(dto.getTitle());
            plan.setStartDate(dto.getStartDate());
            plan.setEndDate(dto.getEndDate());
            this.save(plan);
        } else {
            // 更新现有计划（校验权限）
            plan = this.getById(dto.getId());
            if (plan == null || !userId.equals(plan.getUserId())) {
                throw new RuntimeException("计划不存在或无权限");
            }
            plan.setTitle(dto.getTitle());
            plan.setStartDate(dto.getStartDate());
            plan.setEndDate(dto.getEndDate());
            this.updateById(plan);
        }

        // 保存地点项和日期信息
        this.savePlanItemsAndDays(plan.getId(), dto.getItems());

        return plan;
    }

    /**
     * 根据计划ID获取 PlanSaveDTO（含地点项及关联 place 信息）
     */
    public PlanSaveDTO getPlanSaveDTO(Long planId) {
        TravelPlan plan = this.getById(planId);
        if (plan == null) {
            throw new RuntimeException("计划不存在");
        }

        PlanSaveDTO dto = new PlanSaveDTO();
        dto.setId(plan.getId());
        dto.setTitle(plan.getTitle());
        dto.setStartDate(plan.getStartDate());
        dto.setEndDate(plan.getEndDate());

        // 从 plan_item 表查询（已关联 place 信息）
        List<Map<String, Object>> items = planItemMapper.selectWithPlaceByPlanId(planId);
        // 转换为 camelCase 供前端使用
        List<Map<String, Object>> mappedItems = items.stream().map(item -> {
            Map<String, Object> mapped = new HashMap<>();
            mapped.put("id", item.get("id"));
            mapped.put("placeId", item.get("place_id"));
            mapped.put("dayDate", item.get("day_date"));
            mapped.put("notes", item.get("notes"));
            mapped.put("duration", item.get("duration"));
            mapped.put("name", item.get("place_name"));
            mapped.put("address", item.get("place_address"));
            mapped.put("type", item.get("place_type"));
            mapped.put("city", item.get("place_city"));
            mapped.put("longitude", item.get("place_longitude"));
            mapped.put("latitude", item.get("place_latitude"));
            mapped.put("price", item.get("place_price"));
            return mapped;
        }).collect(Collectors.toList());
        dto.setItems(mappedItems);

        // 根据 items 中的 dayDate 去重，构造 days 列表
        List<Map<String, Object>> daysList = mappedItems.stream()
                .map(item -> (String) item.get("dayDate"))
                .filter(dayDate -> dayDate != null && !dayDate.isEmpty())
                .distinct()
                .sorted()
                .map(dayDate -> {
                    Map<String, Object> day = new HashMap<>();
                    day.put("date", dayDate);
                    try {
                        LocalDate d = LocalDate.parse(dayDate);
                        String[] weekDays = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
                        int index = d.getDayOfWeek().getValue() % 7;
                        day.put("label", String.format("%02d.%02d %s",
                                d.getMonthValue(), d.getDayOfMonth(), weekDays[index]));
                    } catch (Exception e) {
                        day.put("label", dayDate);
                    }
                    return day;
                })
                .collect(Collectors.toList());

        return dto;
    }

    /**
     * 获取指定用户的计划列表（未删除的）
     */
    public List<TravelPlan> getPlansByUserId(Long userId) {
        return this.lambdaQuery()
                .eq(TravelPlan::getUserId, userId)
                .eq(TravelPlan::getDeleted, 0)
                .orderByDesc(TravelPlan::getCreateTime)
                .list();
    }

    /**
     * 逻辑删除计划（设置 deleted=1）
     */
    public void deletePlanLogically(Long planId) {
        lambdaUpdate()
                .eq(TravelPlan::getId, planId)
                .set(TravelPlan::getDeleted, 1)
                .update();
    }

    public PlanItemMapper getPlanItemMapper() {
        return planItemMapper;
    }
}
