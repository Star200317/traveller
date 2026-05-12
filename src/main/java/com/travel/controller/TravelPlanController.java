package com.travel.controller;

import com.travel.common.Result;
import com.travel.dto.PlanSaveDTO;
import com.travel.entity.PlanItem;
import com.travel.entity.TravelPlan;
import com.travel.service.PdfExportService;
import com.travel.service.TravelPlanService;
import cn.dev33.satoken.stp.StpUtil;
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

    /**
     * 创建新计划
     */
    @PostMapping("/create")
    public Result<TravelPlan> createPlan(@RequestBody TravelPlan plan) {
        plan.setUserId(StpUtil.getLoginIdAsLong());
        travelPlanService.save(plan);
        return Result.success(plan);
    }

    /**
     * 获取当前用户的计划列表
     */
    @GetMapping("/list")
    public Result<List<TravelPlan>> getPlanList() {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.success(travelPlanService.getPlansByUserId(userId));
    }

    /**
     * 更新计划
     */
    @PutMapping("/{planId}")
    public Result<Void> updatePlan(@PathVariable Long planId, @RequestBody TravelPlan plan) {
        plan.setId(planId);
        travelPlanService.updateById(plan);
        return Result.success();
    }

    /**
     * 保存计划（包含计划信息和地点项，合并新建/更新）
     * 根据 dto.id 是否为 null 判断是新建还是更新
     */
    @PostMapping("/save")
    public Result<TravelPlan> savePlan(@RequestBody PlanSaveDTO dto) {
        TravelPlan plan = travelPlanService.savePlanWithItems(dto);
        return Result.success(plan);
    }

    /**
     * 获取计划详情（DTO格式，含地点项及 place 信息）
     */
    @GetMapping("/{planId}/dto")
    public Result<PlanSaveDTO> getPlanDTO(@PathVariable Long planId) {
        PlanSaveDTO dto = travelPlanService.getPlanSaveDTO(planId);
        return Result.success(dto);
    }

    /**
     * 新增单个计划项（废弃，使用 /{planId}/items 代替）
     * 原实现有问题：一个方法不能有两个 @RequestBody 参数
     */

    /**
     * 获取计划详情
     */
    @GetMapping("/{planId}/detail")
    public Result<TravelPlan> getPlanDetail(@PathVariable Long planId) {
        TravelPlan plan = travelPlanService.getById(planId);
        if (plan == null) {
            return Result.error("计划不存在");
        }
        return Result.success(plan);
    }

    /**
     * 删除计划（逻辑删除）
     */
    @DeleteMapping("/{planId}")
    public Result<Void> deletePlan(@PathVariable Long planId) {
        travelPlanService.deletePlanLogically(planId);
        return Result.success();
    }

    /**
     * 导出计划为 PDF
     */
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
