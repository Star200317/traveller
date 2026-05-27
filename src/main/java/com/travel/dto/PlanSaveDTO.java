package com.travel.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 计划保存 DTO
 * 包含计划基本信息 + 行程地点项列表
 */
@Data
public class PlanSaveDTO {
    /**
     * 计划ID，null 表示新建，非 null 表示更新
     */
    private Long id;

    /**
     * 计划标题（对应 TravelPlan.title）
     */
    private String title;

    /**
     * 开始日期（对应 TravelPlan.startDate）
     */
    private LocalDate startDate;

    /**
     * 结束日期（对应 TravelPlan.endDate）
     */
    private LocalDate endDate;

    /**
     * 行程地点项列表
     * 每项包含：placeId, dayDate, notes, duration, sortOrder
     */
    private List<Map<String, Object>> items;

    /**
     * 行程日期列表
     * 每项包含：date, label
     */
    private List<Map<String, Object>> days;

}
