package com.travel.service;

import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.action.PdfAction;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Link;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.properties.TextAlignment;
import com.travel.entity.TravelPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PdfExportService {

    private final TravelPlanService travelPlanService;

    @Value("${file.pdf-path:./exports/pdf}")
    private String pdfPath;

    // 中文字体路径（Windows系统字体目录）
    private static final String FONT_SIMHEI = "C:\\Windows\\Fonts\\simhei.ttf";      // 黑体
    private static final String FONT_SIMSUN = "C:\\Windows\\Fonts\\simsun.ttc,0";     // 宋体

    public String exportPlan(Long planId) throws Exception {
        TravelPlan plan = travelPlanService.getById(planId);
        if (plan == null) throw new RuntimeException("计划不存在");

        Files.createDirectories(Paths.get(pdfPath));
        String fileName = "travel-plan-" + planId + ".pdf";
        String filePath = pdfPath + File.separator + fileName;

        // 尝试加载中文字体，失败则使用内置字体
        PdfFont font;
        PdfFont boldFont;
        try {
            File fontFile = new File(FONT_SIMHEI);
            if (fontFile.exists()) {
                font = PdfFontFactory.createFont(FONT_SIMHEI, PdfFontFactory.EmbeddingStrategy.FORCE_EMBEDDED);
                boldFont = font;
            } else {
                font = PdfFontFactory.createFont(StandardFonts.HELVETICA);
                boldFont = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD);
            }
        } catch (Exception e) {
            log.warn("[PDF] 字体加载失败，使用内置字体: {}", e.getMessage());
            font = PdfFontFactory.createFont(StandardFonts.HELVETICA);
            boldFont = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD);
        }

        try (PdfWriter writer = new PdfWriter(filePath);
             PdfDocument pdf = new PdfDocument(writer);
             Document doc = new Document(pdf)) {

            // 标题
            Paragraph title = new Paragraph(plan.getTitle())
                    .setFont(boldFont).setFontSize(22)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setFontColor(ColorConstants.DARK_GRAY);
            doc.add(title);

            // 基本信息
            doc.add(new Paragraph("目的地：" + plan.getDestination()).setFont(font).setFontSize(12));
            if (plan.getStartDate() != null) {
                doc.add(new Paragraph("出行日期：" + plan.getStartDate() + " 至 " + plan.getEndDate()).setFont(font));
            }
            doc.add(new Paragraph("天数：" + plan.getDays() + " 天  人数：" + plan.getPeopleCount() + " 人").setFont(font));
            if (plan.getBudget() != null) {
                doc.add(new Paragraph("预算：" + plan.getBudget() + " 元").setFont(font));
            }

            doc.add(new Paragraph("\n"));

            // 每日行程
            Map<String, Object> content = plan.getPlanContent();
            if (content != null && content.containsKey("days")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> days = (List<Map<String, Object>>) content.get("days");
                for (Map<String, Object> day : days) {
                    // 支持两种字段名：day 或 dayIndex
                    int dayIdx = 0;
                    if (day.containsKey("day")) {
                        Object dayVal = day.get("day");
                        dayIdx = dayVal instanceof Number ? ((Number) dayVal).intValue() : 0;
                    } else if (day.containsKey("dayIndex")) {
                        Object dayVal = day.get("dayIndex");
                        dayIdx = dayVal instanceof Number ? ((Number) dayVal).intValue() : 0;
                    }
                    String dayTitle = (String) day.getOrDefault("title", "第" + dayIdx + "天");
                    doc.add(new Paragraph("Day " + dayIdx + "  " + dayTitle)
                            .setFont(boldFont).setFontSize(14)
                            .setFontColor(ColorConstants.BLUE));

                    // 支持两种字段名：activities 或 attractions
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> activities = (List<Map<String, Object>>) day.getOrDefault("activities",
                            day.getOrDefault("attractions", List.of()));

                    if (activities.isEmpty()) {
                        doc.add(new Paragraph("  （暂无详细行程）").setFont(font).setFontSize(11));
                    } else {
                        for (int i = 0; i < activities.size(); i++) {
                            Map<String, Object> act = activities.get(i);
                            String name = (String) act.getOrDefault("name", "未命名");
                            String time = act.containsKey("time") ? "[" + act.get("time") + "] " : "";
                            String ticket = act.containsKey("ticket") ? "  门票：" + act.get("ticket") : "";
                            
                            // 景点名称和时间
                            doc.add(new Paragraph("  " + (i + 1) + ". " + time + name + ticket)
                                    .setFont(font).setFontSize(11));

                            // 描述信息
                            if (act.containsKey("description")) {
                                doc.add(new Paragraph("     " + act.get("description"))
                                        .setFont(font).setFontSize(10).setFontColor(ColorConstants.GRAY));
                            }
                            // 地址信息
                            if (act.containsKey("address") && !((String)act.getOrDefault("address", "")).isEmpty()) {
                                doc.add(new Paragraph("     地址：" + act.get("address"))
                                        .setFont(font).setFontSize(10).setFontColor(ColorConstants.GRAY));
                            }
                        }
                    }

                    // 酒店信息
                    @SuppressWarnings("unchecked")
                    Map<String, Object> hotel = (Map<String, Object>) day.get("hotel");
                    if (hotel != null) {
                        String hotelName = (String) hotel.getOrDefault("name", "");
                        String hotelPrice = (String) hotel.getOrDefault("price", "");
                        String hotelAddress = (String) hotel.getOrDefault("address", "");
                        
                        Paragraph hotelPara = new Paragraph()
                                .add("  酒店：")
                                .add(hotelName)
                                .setFont(boldFont)
                                .setFontSize(11)
                                .setFontColor(ColorConstants.DARK_GRAY);
                        doc.add(hotelPara);
                        
                        if (!hotelPrice.isEmpty()) {
                            doc.add(new Paragraph("       价格：" + hotelPrice)
                                    .setFont(font).setFontSize(10).setFontColor(ColorConstants.GRAY));
                        }
                        if (!hotelAddress.isEmpty()) {
                            doc.add(new Paragraph("       地址：" + hotelAddress)
                                    .setFont(font).setFontSize(10).setFontColor(ColorConstants.GRAY));
                        }
                    }
                    
                    // 兼容旧数据的住宿字段
                    if (day.containsKey("accommodation")) {
                        doc.add(new Paragraph("  住宿：" + day.get("accommodation")).setFont(font).setFontSize(11));
                    }
                    doc.add(new Paragraph("\n"));
                }
            }

            // 地图查看链接
            String mapUrl = "http://localhost:5173/map/" + planId;
            Link mapLink = new Link("点击在浏览器中查看地图路线（显示景点标点和路径）", PdfAction.createURI(mapUrl));
            Paragraph mapPara = new Paragraph()
                    .add("地图路线：")
                    .add(mapLink)
                    .setFont(font).setFontSize(11)
                    .setTextAlignment(TextAlignment.CENTER);
            doc.add(mapPara);

            doc.add(new Paragraph("\n"));

            // 页脚
            doc.add(new Paragraph("由 AI旅游向导 生成")
                    .setFont(font).setFontSize(9)
                    .setTextAlignment(TextAlignment.RIGHT)
                    .setFontColor(ColorConstants.LIGHT_GRAY));
        }

        // 更新状态
        plan.setStatus(3);
        travelPlanService.updateById(plan);
        log.info("[PDF] 导出成功: {}", filePath);
        return filePath;
    }

    public String getPdfFilePath(Long planId) {
        return pdfPath + File.separator + "travel-plan-" + planId + ".pdf";
    }
}
