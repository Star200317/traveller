package com.travel.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.travel.common.Result;
import com.travel.dto.PlanSaveDTO;
import com.travel.entity.TravelPlan;
import com.travel.service.AmapService;
import com.travel.service.PdfExportService;
import com.travel.service.TravelPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/plan")
@RequiredArgsConstructor
public class TravelPlanController {

    private final TravelPlanService travelPlanService;
    private final PdfExportService pdfExportService;
    private final AmapService amapService;

    @PostMapping("/create")
    public Result<TravelPlan> createPlan(@RequestBody TravelPlan plan) {
        plan.setUserId(StpUtil.getLoginIdAsLong());
        travelPlanService.save(plan);
        return Result.success(plan);
    }

    @GetMapping("/list")
    public Result<List<TravelPlan>> getPlanList() {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.success(travelPlanService.getPlansByUserId(userId));
    }

    @PutMapping("/{planId}")
    public Result<Void> updatePlan(@PathVariable Long planId, @RequestBody TravelPlan plan) {
        plan.setId(planId);
        travelPlanService.updateById(plan);
        return Result.success();
    }

    @PostMapping("/save")
    public Result<TravelPlan> savePlan(@RequestBody PlanSaveDTO dto) {
        TravelPlan plan = travelPlanService.savePlanWithItems(dto);
        return Result.success(plan);
    }

    @GetMapping("/{planId}/dto")
    public Result<PlanSaveDTO> getPlanDTO(@PathVariable Long planId) {
        PlanSaveDTO dto = travelPlanService.getPlanSaveDTO(planId);
        return Result.success(dto);
    }

    @GetMapping("/{planId}")
    public Result<TravelPlan> getPlan(@PathVariable Long planId) {
        TravelPlan plan = travelPlanService.getById(planId);
        if (plan == null) {
            return Result.error("计划不存在");
        }
        return Result.success(plan);
    }

    @GetMapping("/byConv/{conversationId}")
    public Result<TravelPlan> getPlanByConversation(@PathVariable Long conversationId) {
        TravelPlan plan = travelPlanService.getLatestPlanByUserId(StpUtil.getLoginIdAsLong());
        if (plan == null) {
            return Result.error("未找到可用计划");
        }
        return Result.success(plan);
    }

    @PostMapping("/route/driving")
    public Result<Map<String, Object>> planDrivingRoute(@RequestBody Map<String, Object> body) {
        Object pointsObj = body.get("points");
        if (!(pointsObj instanceof List<?> rawPoints) || rawPoints.size() < 2) {
            return Result.error("至少需要两个坐标点");
        }

        List<Map<String, Object>> normalizedPoints = rawPoints.stream()
                .filter(Map.class::isInstance)
                .map(point -> {
                    Map<?, ?> rawMap = (Map<?, ?>) point;
                    Map<String, Object> normalized = new java.util.HashMap<>();
                    rawMap.forEach((key, value) -> normalized.put(String.valueOf(key), value));
                    return normalized;
                })
                .toList();

        List<Map<String, Object>> segments = amapService.planRoutes(normalizedPoints);
        return Result.success(Map.of("segments", segments));
    }

    @GetMapping("/{planId}/detail")
    public Result<TravelPlan> getPlanDetail(@PathVariable Long planId) {
        TravelPlan plan = travelPlanService.getById(planId);
        if (plan == null) {
            return Result.error("计划不存在");
        }
        return Result.success(plan);
    }

    @DeleteMapping("/{planId}")
    public Result<Void> deletePlan(@PathVariable Long planId) {
        travelPlanService.deletePlanLogically(planId);
        return Result.success();
    }

    @GetMapping("/{planId}/export/pdf")
    public ResponseEntity<byte[]> exportPdf(@PathVariable Long planId) throws Exception {
        PlanSaveDTO dto = travelPlanService.getPlanSaveDTO(planId);
        byte[] pdfBytes = pdfExportService.exportPlan(dto);
        String fileName = URLEncoder.encode(
                (dto.getTitle() != null ? dto.getTitle() : "旅游计划") + ".pdf",
                StandardCharsets.UTF_8
        );

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }
}
