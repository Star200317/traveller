package com.travel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.entity.Place;
import org.apache.ibatis.annotations.Mapper;

/**
 * 地点Mapper
 */
@Mapper
public interface PlaceMapper extends BaseMapper<Place> {
}
