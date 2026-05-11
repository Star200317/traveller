package com.travel.controller;

import com.travel.common.Result;
import com.travel.entity.Place;
import com.travel.service.PlaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 地点控制器
 */
@RestController
@RequestMapping("/places")
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*")
public class PlaceController {

    private final PlaceService placeService;

    /**
     * 保存或查询地点（如果已存在则返回已有记录）
     */
    @PostMapping("/save")
    public Result<Place> savePlace(@RequestBody Place place) {
        Place saved = placeService.findOrCreate(
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
        return Result.success(saved);
    }

    /**
     * 根据名称查询地点
     */
    @GetMapping("/search")
    public Result<Place> searchByName(@RequestParam String name) {
        Place place = placeService.findByName(name, null);
        return Result.success(place);
    }

    /**
     * 根据城市和类型查询地点列表
     */
    @GetMapping("/list")
    public Result<List<Place>> listByCityAndType(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String type) {
        List<Place> places = placeService.findByCityAndType(city, type);
        return Result.success(places);
    }

    /**
     * 获取地点详情
     */
    @GetMapping("/{id}")
    public Result<Place> getById(@PathVariable Long id) {
        Place place = placeService.getById(id);
        return Result.success(place);
    }

    /**
     * 获取所有城市列表（去重）
     */
    @GetMapping("/cities")
    public Result<List<String>> getAllCities() {
        List<Place> places = placeService.list();
        List<String> cities = places.stream()
                .map(Place::getCity)
                .filter(city -> city != null && !city.isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        return Result.success(cities);
    }

    /**
     * 获取地点统计信息（按类型分组）
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> getStats(@RequestParam(required = false) String city) {
        List<Place> places = placeService.findByCityAndType(city, null);
        
        long hotels = places.stream().filter(p -> "hotel".equals(p.getType())).count();
        long attractions = places.stream().filter(p -> "attraction".equals(p.getType())).count();
        long restaurants = places.stream().filter(p -> "restaurant".equals(p.getType())).count();
        
        Map<String, Object> stats = Map.of(
                "total", places.size(),
                "hotels", hotels,
                "attractions", attractions,
                "restaurants", restaurants
        );
        return Result.success(stats);
    }

    /**
     * 获取推荐地点（带坐标的热门地点）
     */
    @GetMapping("/recommend")
    public Result<List<Place>> getRecommendPlaces(
            @RequestParam(required = false) String city,
            @RequestParam(defaultValue = "10") Integer limit) {
        List<Place> places = placeService.findByCityAndType(city, null);
        // 只返回有坐标的地点，按创建时间降序
        List<Place> validPlaces = places.stream()
                .filter(p -> p.getLatitude() != null && p.getLongitude() != null)
                .sorted((a, b) -> b.getCreateTime().compareTo(a.getCreateTime()))
                .limit(limit)
                .collect(Collectors.toList());
        return Result.success(validPlaces);
    }

    /**
     * 批量获取地点（根据ID列表）
     */
    @PostMapping("/batch")
    public Result<List<Place>> getBatchPlaces(@RequestBody List<Long> ids) {
        List<Place> places = placeService.listByIds(ids);
        return Result.success(places);
    }

    /**
     * 更新地点信息
     */
    @PutMapping("/{id}")
    public Result<Place> updatePlace(@PathVariable Long id, @RequestBody Place place) {
        Place existing = placeService.getById(id);
        if (existing == null) {
            return Result.fail("地点不存在");
        }
        // 只更新非空字段
        if (place.getName() != null) existing.setName(place.getName());
        if (place.getAddress() != null) existing.setAddress(place.getAddress());
        if (place.getType() != null) existing.setType(place.getType());
        if (place.getDescription() != null) existing.setDescription(place.getDescription());
        if (place.getCity() != null) existing.setCity(place.getCity());
        if (place.getPrice() != null) existing.setPrice(place.getPrice());
        if (place.getLatitude() != null) existing.setLatitude(place.getLatitude());
        if (place.getLongitude() != null) existing.setLongitude(place.getLongitude());
        if (place.getSource() != null) existing.setSource(place.getSource());
        
        placeService.updateById(existing);
        return Result.success(existing);
    }

    /**
     * 删除地点（逻辑删除）
     */
    @DeleteMapping("/{id}")
    public Result<Void> deletePlace(@PathVariable Long id) {
        Place existing = placeService.getById(id);
        if (existing == null) {
            return Result.fail("地点不存在");
        }
        existing.setDeleted(1);
        placeService.updateById(existing);
        return Result.success();
    }
}
