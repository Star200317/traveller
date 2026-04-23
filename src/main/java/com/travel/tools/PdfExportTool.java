package com.travel.tools;

import com.travel.service.PdfExportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 工具7：PDF导出
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdfExportTool {

    private final PdfExportService pdfExportService;

    @Tool(description = "将已确认的旅游计划导出为PDF文件，包含行程安排、地图路线、景点信息等。必须在调用save_plan之后使用，planId参数必须是save_plan工具返回的长数字ID")
    public String exportPdf(
            @ToolParam(description = "要导出的旅游计划ID，必须是save_plan工具返回的长数字（如2045786267961270273），不能自己编造") Long planId) {
        try {
            String filePath = pdfExportService.exportPlan(planId);
            return "PDF已生成，下载地址：/api/plan/" + planId + "/pdf/download";
        } catch (Exception e) {
            log.error("[PdfExport] 导出失败, planId={}", planId, e);
            return "PDF导出失败：" + e.getMessage();
        }
    }
}
