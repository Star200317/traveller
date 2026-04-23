package com.travel.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@TableName(value = "message", autoResultMap = true)
public class Message {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long conversationId;
    private String role;     // user / assistant / tool
    private String content;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> toolCalls;

    private Integer tokens;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
