package com.travel.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 计划项表（每天每个地点）
 * 通过 place_id 关联 place 表获取详细信息
 */
@Data
@TableName("plan_item")
public class PlanItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long planId;      // 所属计划ID（关联 travel_plan.id）
    private Long placeId;      // 关联地点ID（关联 place.id）
    private String dayDate;    // 当天日期（如：2026-04-15，NULL表示待计划）
    private Integer sortOrder;  // 当天内的排序权重
    private String notes;       // 备注
    private String duration;    // 建议游览时长

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
