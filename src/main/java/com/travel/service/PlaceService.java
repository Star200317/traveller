package com.travel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.travel.entity.Place;
import com.travel.mapper.PlaceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 地点服务：管理酒店、景点、餐厅等地点信息
 * 
 * 功能：
 * 1. 根据名称查询地点（查重）
 * 2. 插入新地点
 * 3. 获取地点地址（供高德地图使用）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceService extends ServiceImpl<PlaceMapper, Place> {

    /**
     * 根据名称和类型查询地点
     */
    public Place findByName(String name, String type) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        
        LambdaQueryWrapper<Place> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Place::getName, name)
               .eq(type != null, Place::getType, type)
               .eq(Place::getDeleted, 0)
               .last("LIMIT 1");
        
        return getOne(wrapper);
    }

    /**
     * 根据地址查询地点
     */
    public Place findByAddress(String address) {
        if (address == null || address.isEmpty()) {
            return null;
        }
        
        LambdaQueryWrapper<Place> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Place::getAddress, address)
               .eq(Place::getDeleted, 0)
               .last("LIMIT 1");
        
        return getOne(wrapper);
    }

    /**
     * 查询或插入地点
     * 
     * @param name 名称
     * @param address 地址
     * @param type 类型
     * @param description 简介
     * @param city 城市
     * @param price 价格
     * @param latitude 纬度
     * @param longitude 经度
     * @param source 来源
     * @return 已存在或新插入的地点
     */
    public Place findOrCreate(String name, String address, String type, String description,
                             String city, String price, BigDecimal latitude, 
                             BigDecimal longitude, String source) {
        // 1. 先按名称+类型查重
        Place existing = findByName(name, type);
        if (existing != null) {
            log.debug("[Place] 地点已存在: name={}, type={}", name, type);
            return existing;
        }

        // 2. 按地址查重（名称可能有细微差异）
        if (address != null && !address.isEmpty()) {
            existing = findByAddress(address);
            if (existing != null) {
                log.debug("[Place] 地点已存在（按地址）: address={}", address);
                return existing;
            }
        }

        // 3. 创建新地点
        Place place = new Place();
        place.setName(name);
        place.setAddress(address);
        place.setType(type);
        place.setDescription(description);
        place.setCity(city);
        place.setPrice(price);
        place.setLatitude(latitude);
        place.setLongitude(longitude);
        place.setSource(source);
        place.setDeleted(0);

        save(place);
        log.info("[Place] 新增地点: name={}, address={}, type={}", name, address, type);

        return place;
    }

    /**
     * 更新地点的坐标
     */
    public void updateCoordinates(Long placeId, BigDecimal latitude, BigDecimal longitude) {
        Place place = getById(placeId);
        if (place != null) {
            place.setLatitude(latitude);
            place.setLongitude(longitude);
            updateById(place);
            log.info("[Place] 更新坐标: id={}, lat={}, lng={}", placeId, latitude, longitude);
        }
    }

    /**
     * 根据城市和类型获取地点列表
     */
    public List<Place> findByCityAndType(String city, String type) {
        LambdaQueryWrapper<Place> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(city != null, Place::getCity, city)
               .eq(type != null, Place::getType, type)
               .eq(Place::getDeleted, 0);
        return list(wrapper);
    }

    /**
     * 批量插入地点
     */
    public void batchSave(List<Place> places) {
        for (Place place : places) {
            findOrCreate(
                place.getName(),
                place.getAddress(),
                place.getType(),
                place.getDescription(),
                place.getCity(),
                place.getPrice(),
                place.getLatitude(),
                place.getLongitude(),
                place.getSource()
            );
        }
    }
}
