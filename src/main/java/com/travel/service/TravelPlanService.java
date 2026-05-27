package com.travel.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.travel.dto.PlanSaveDTO;
import com.travel.entity.PlanItem;
import com.travel.entity.TravelPlan;
import com.travel.mapper.PlanItemMapper;
import com.travel.mapper.TravelPlanMapper;
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

    @Transactional
    public void savePlanItemsAndDays(Long planId, List<Map<String, Object>> items) {
        planItemMapper.deleteByPlanId(planId);

        if (items != null) {
            Map<String, Integer> dayOrderFallback = new HashMap<>();
            for (Map<String, Object> itemMap : items) {
                PlanItem item = new PlanItem();
                item.setPlanId(planId);

                Object placeIdObj = itemMap.get("placeId");
                if (placeIdObj != null && !"".equals(placeIdObj.toString().trim())) {
                    try {
                        item.setPlaceId(Long.valueOf(placeIdObj.toString()));
                    } catch (NumberFormatException e) {
                        item.setPlaceId(null);
                    }
                }

                Object dayDateObj = itemMap.get("dayDate");
                if (dayDateObj != null && !"".equals(dayDateObj.toString().trim())) {
                    item.setDayDate(dayDateObj.toString());
                } else {
                    Object dayIndexObj = itemMap.get("dayIndex");
                    if (dayIndexObj != null) {
                        try {
                            int idx = Integer.parseInt(dayIndexObj.toString());
                            item.setDayDate("第" + (idx + 1) + "天");
                        } catch (NumberFormatException e) {
                            item.setDayDate(null);
                        }
                    } else {
                        item.setDayDate(null);
                    }
                }

                Integer sortOrder = parseInteger(itemMap.get("sortOrder"));
                if (sortOrder == null || sortOrder <= 0) {
                    String dayKey = item.getDayDate() == null || item.getDayDate().isBlank()
                            ? "__UNPLANNED__"
                            : item.getDayDate();
                    int nextOrder = dayOrderFallback.getOrDefault(dayKey, 0) + 1;
                    dayOrderFallback.put(dayKey, nextOrder);
                    sortOrder = nextOrder;
                }
                item.setSortOrder(sortOrder);
                item.setNotes(itemMap.containsKey("notes") && itemMap.get("notes") != null
                        ? itemMap.get("notes").toString() : null);
                item.setDuration(itemMap.containsKey("duration") && itemMap.get("duration") != null
                        ? itemMap.get("duration").toString() : null);
                planItemMapper.insert(item);
            }
        }
    }

    @Transactional
    public TravelPlan savePlanWithItems(PlanSaveDTO dto) {
        TravelPlan plan;
        Long userId = StpUtil.getLoginIdAsLong();

        if (dto.getId() == null) {
            plan = new TravelPlan();
            plan.setUserId(userId);
            plan.setTitle(dto.getTitle());
            plan.setStartDate(dto.getStartDate());
            plan.setEndDate(dto.getEndDate());
            this.save(plan);
        } else {
            plan = this.getById(dto.getId());
            if (plan == null || !userId.equals(plan.getUserId())) {
                throw new RuntimeException("计划不存在或无权限");
            }
            plan.setTitle(dto.getTitle());
            plan.setStartDate(dto.getStartDate());
            plan.setEndDate(dto.getEndDate());
            this.updateById(plan);
        }

        this.savePlanItemsAndDays(plan.getId(), dto.getItems());
        return plan;
    }

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

        List<Map<String, Object>> items = planItemMapper.selectWithPlaceByPlanId(planId);
        List<Map<String, Object>> mappedItems = items.stream().map(item -> {
            Map<String, Object> mapped = new HashMap<>();
            mapped.put("id", item.get("id"));
            mapped.put("placeId", item.get("place_id"));
            mapped.put("dayDate", item.get("day_date"));
            mapped.put("sortOrder", item.get("sort_order"));
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
        }).sorted((a, b) -> {
            String dayA = (String) a.get("dayDate");
            String dayB = (String) b.get("dayDate");
            if (dayA == null && dayB != null) return 1;
            if (dayA != null && dayB == null) return -1;
            if (dayA != null) {
                int dayCmp = dayA.compareTo(dayB);
                if (dayCmp != 0) return dayCmp;
            }

            Integer orderA = parseInteger(a.get("sortOrder"));
            Integer orderB = parseInteger(b.get("sortOrder"));
            int safeOrderA = orderA == null ? Integer.MAX_VALUE : orderA;
            int safeOrderB = orderB == null ? Integer.MAX_VALUE : orderB;
            if (safeOrderA != safeOrderB) return Integer.compare(safeOrderA, safeOrderB);

            Long idA = parseLong(a.get("id"));
            Long idB = parseLong(b.get("id"));
            long safeIdA = idA == null ? Long.MAX_VALUE : idA;
            long safeIdB = idB == null ? Long.MAX_VALUE : idB;
            return Long.compare(safeIdA, safeIdB);
        }).collect(Collectors.toList());
        dto.setItems(mappedItems);

        List<Map<String, Object>> daysList = new ArrayList<>();
        if (plan.getStartDate() != null && plan.getEndDate() != null && !plan.getEndDate().isBefore(plan.getStartDate())) {
            LocalDate cursor = plan.getStartDate();
            while (!cursor.isAfter(plan.getEndDate())) {
                daysList.add(buildDayDto(cursor));
                cursor = cursor.plusDays(1);
            }
        } else {
            daysList = mappedItems.stream()
                    .map(item -> (String) item.get("dayDate"))
                    .filter(dayDate -> dayDate != null && !dayDate.isEmpty())
                    .distinct()
                    .sorted()
                    .map(dayDate -> {
                        try {
                            return buildDayDto(LocalDate.parse(dayDate));
                        } catch (Exception e) {
                            Map<String, Object> day = new HashMap<>();
                            day.put("date", dayDate);
                            day.put("label", dayDate);
                            return day;
                        }
                    })
                    .collect(Collectors.toList());
        }

        dto.setDays(daysList);
        return dto;
    }

    private Integer parseInteger(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Object> buildDayDto(LocalDate date) {
        Map<String, Object> day = new HashMap<>();
        String[] weekDays = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        int index = date.getDayOfWeek().getValue() % 7;
        day.put("date", date.toString());
        day.put("label", String.format("%02d.%02d %s",
                date.getMonthValue(), date.getDayOfMonth(), weekDays[index]));
        return day;
    }

    public List<TravelPlan> getPlansByUserId(Long userId) {
        return this.lambdaQuery()
                .eq(TravelPlan::getUserId, userId)
                .eq(TravelPlan::getDeleted, 0)
                .orderByDesc(TravelPlan::getCreateTime)
                .list();
    }

    public TravelPlan getLatestPlanByUserId(Long userId) {
        return this.lambdaQuery()
                .eq(TravelPlan::getUserId, userId)
                .eq(TravelPlan::getDeleted, 0)
                .orderByDesc(TravelPlan::getCreateTime)
                .last("LIMIT 1")
                .one();
    }

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
