package com.travel.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.travel.common.Result;
import com.travel.entity.TravelPlan;
import com.travel.service.PdfExportService;
import com.travel.service.TravelPlanService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.file.Files;
import java.util.List;

@RestController
@RequestMapping("/plan")
@RequiredArgsConstructor
public class TravelPlanController {

    private final TravelPlanService travelPlanService;
    private final PdfExportService pdfExportService;

    @GetMapping("/list")
    public Result<List<TravelPlan>> list() {
        Long userId = StpUtil.getLoginIdAsLong();
        // 按创建时间倒序，过滤已删除，确保最新计划在前
        return Result.success(travelPlanService.lambdaQuery()
            .eq(TravelPlan::getUserId, userId)
            .eq(TravelPlan::getDeleted, 0)
            .orderByDesc(TravelPlan::getCreateTime)
            .list());
    }

    /**
     * 通过对话ID查询关联的计划（地图页跳转用）
     */
    @GetMapping("/byConv/{convId}")
    public Result<TravelPlan> getByConvId(@PathVariable Long convId) {
        TravelPlan plan = travelPlanService.lambdaQuery()
            .eq(TravelPlan::getConversationId, convId)
            .one();
        return Result.success(plan);
    }

    @GetMapping("/{planId}")
    public Result<TravelPlan> detail(@PathVariable Long planId) {
        TravelPlan plan = travelPlanService.getById(planId);
        if (plan != null) {
            System.out.println("[PlanController] 查询planId=" + planId + ", mapData=" + (plan.getMapData() != null ? "存在" : "null"));
        }
        return Result.success(plan);
    }

    @DeleteMapping("/{planId}")
    public Result<Void> delete(@PathVariable Long planId) {
        travelPlanService.deletePlan(planId);
        return Result.success();
    }

    /**
     * 获取地图数据（前端渲染高德地图）
     */
    @GetMapping("/{planId}/map")
    public Result<Object> getMapData(@PathVariable Long planId) {
        TravelPlan plan = travelPlanService.getById(planId);
        return Result.success(plan.getMapData());
    }

    /**
     * 导出PDF
     */
    @PostMapping("/{planId}/pdf/export")
    public Result<String> exportPdf(@PathVariable Long planId) throws Exception {
        pdfExportService.exportPlan(planId);
        return Result.success("/api/plan/" + planId + "/pdf/download");
    }

    /**
     * 下载PDF
     */
    @GetMapping("/{planId}/pdf/download")
    public void downloadPdf(@PathVariable Long planId, HttpServletResponse response) throws Exception {
        String filePath = pdfExportService.getPdfFilePath(planId);
        File file = new File(filePath);
        if (!file.exists()) {
            pdfExportService.exportPlan(planId);
        }
        response.setContentType("application/pdf");
        response.setHeader("Content-Disposition", "attachment; filename=travel-plan-" + planId + ".pdf");
        try (InputStream is = new FileInputStream(file);
             OutputStream os = response.getOutputStream()) {
            is.transferTo(os);
        }
    }
}
