package com.travel.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 地点表：存储酒店、景点、餐厅等地点信息
 * AI查询后先入库，高德地图从此表获取地址
 */
@Data
@TableName("place")
public class Place {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 地点名称 */
    private String name;

    /** 详细地址（精确到门牌号） */
    private String address;

    /** 简介 */
    private String description;

    /** 类型：hotel=酒店, attraction=景点, restaurant=餐厅 */
    private String type;

    /** 所属城市 */
    private String city;

    /** 经度 */
    private BigDecimal longitude;

    /** 纬度 */
    private BigDecimal latitude;

    /** 价格/晚（酒店）或 人均（餐厅）或 门票（景点） */
    private String price;

    /** 联系电话 */
    private String phone;

    /** 数据来源：knowledge=知识库, web=联网搜索, manual=手动录入 */
    private String source;

    /** 逻辑删除：0=未删除，1=已删除 */
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
