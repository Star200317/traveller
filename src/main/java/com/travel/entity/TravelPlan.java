package com.travel.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "travel_plan", autoResultMap = true)
public class TravelPlan {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private Long conversationId;
    private String title;
    private String destination;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer days;
    private BigDecimal budget;
    private String travelStyle;
    private Integer peopleCount;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> planContent;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> mapData;

    private Integer status;  // 1草稿 2已确认 3已导出

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
