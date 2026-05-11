package com.travel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.entity.PlanItem;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface PlanItemMapper extends BaseMapper<PlanItem> {

    /**
     * 根据计划ID删除所有计划项
     */
    @Delete("DELETE FROM plan_item WHERE plan_id = #{planId}")
    int deleteByPlanId(@Param("planId") Long planId);

    /**
     * 根据计划ID查询所有计划项，按 day_date 和 sortOrder 排序
     */
    @Select("SELECT * FROM plan_item WHERE plan_id = #{planId} ORDER BY day_date ASC, sort_order ASC")
    List<PlanItem> selectByPlanId(@Param("planId") Long planId);

    /**
     * 根据计划ID查询所有计划项，并关联place表获取地点详情
     * 返回Map包含：plan_item所有字段 + place_name, place_address, place_type等
     */
    @Select("""
            SELECT 
                pi.*, 
                p.name AS place_name, 
                p.address AS place_address, 
                p.type AS place_type, 
                p.city AS place_city, 
                p.longitude AS place_longitude, 
                p.latitude AS place_latitude, 
                p.price AS place_price
            FROM plan_item pi
            LEFT JOIN place p ON pi.place_id = p.id
            WHERE pi.plan_id = #{planId}
            ORDER BY pi.day_date ASC, pi.sort_order ASC
            """)
    List<Map<String, Object>> selectWithPlaceByPlanId(@Param("planId") Long planId);
}
