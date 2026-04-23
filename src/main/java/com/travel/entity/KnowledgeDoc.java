package com.travel.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(value = "knowledge_doc", autoResultMap = true)
public class KnowledgeDoc {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;          // null=公共库
    private String category;      // attraction/city/tip/user
    private String title;
    private String content;
    private String fileName;
    private Long fileSize;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> pineconeIds;

    private Integer status;       // 0待处理 1已向量化

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
