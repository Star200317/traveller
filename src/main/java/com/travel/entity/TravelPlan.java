package com.travel.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 旅游计划主表
 * 对应数据库表：travel_plan
 */
@Data
@TableName(value = "travel_plan", autoResultMap = true)
public class TravelPlan {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private String title;
    private String destination;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer status;  // 1=草稿，2=已确认，3=已导出PDF


    // 逻辑删除字段：0=未删除，1=已删除
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
