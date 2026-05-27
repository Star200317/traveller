package com.travel.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 工具：汇率查询（open.er-api.com，完全免费，无需API Key）
 * 用于出境游预算计算
 */
@Slf4j
@Component
public class CurrencyTool {

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(name = "currency_query", description = """
            查询实时汇率，用于出境游预算计算。
            【重要】此工具仅适用于【出境游】场景，国内游不需要调用！

            输出要求：
            - 使用纯文本和普通算式（如：3000 USD × 6.8150 = 20445.00 CNY）
            - 不要使用 LaTeX / \\text / \\approx 等数学标记

            调用时机：
            - 用户询问出境游预算（如"去泰国要换多少泰铢？"）
            - 用户直接问汇率（如"人民币对美元汇率多少？"）
            - 用户询问境外消费（如"在日本吃饭贵吗？" -> 先查汇率再解释）

            参数说明：
            - baseCurrency：基准货币代码（必填），如 CNY（人民币）、USD（美元）、EUR（欧元）
            - targetCurrency：目标货币代码（可选），不填则返回基准货币对所有货币的汇率

            常用货币代码：CNY=人民币、USD=美元、EUR=欧元、JPY=日元、GBP=英镑、
                          THB=泰铢、KRW=韩元、SGD=新加坡元、AUD=澳元
            """)
    public String queryExchangeRate(
            @ToolParam(description = "基准货币代码，如 CNY（人民币）、USD（美元）、EUR（欧元）") String baseCurrency,
            @ToolParam(description = "目标货币代码（可选），如 THB（泰铢）、JPY（日元）。不填则返回所有货币汇率。", required = false) String targetCurrency) {

        if (baseCurrency == null || baseCurrency.trim().isEmpty()) {
            return "❌ 基准货币不能为空！常用代码：CNY（人民币）、USD（美元）、EUR（欧元）";
        }

        try {
            String url = String.format("https://open.er-api.com/v6/latest/%s", baseCurrency.trim().toUpperCase());
            Request request = new Request.Builder().url(url).build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return "❌ 汇率查询失败，HTTP状态码：" + response.code();
                }

                String respBody = response.body().string();
                JsonNode root = objectMapper.readTree(respBody);

                String result = root.has("result") ? root.get("result").asText() : "";
                if (!"success".equals(result)) {
                    String error = root.has("'error-type'") ? root.get("'error-type'").asText() : "未知错误";
                    return String.format("❌ 汇率查询失败：%s。请检查货币代码是否正确（如 CNY、USD）。", error);
                }

                JsonNode rates = root.get("rates");
                if (rates == null || !rates.isObject()) {
                    return "❌ 汇率数据格式异常。";
                }

                String base = baseCurrency.trim().toUpperCase();
                StringBuilder sb = new StringBuilder();
                sb.append("💱 实时汇率（基准：").append(base).append("）\n\n");

                if (targetCurrency != null && !targetCurrency.trim().isEmpty()) {
                    String target = targetCurrency.trim().toUpperCase();
                    JsonNode rate = rates.get(target);
                    if (rate != null) {
                        double rateValue = rate.asDouble();
                        double sampleAmount = 3000.0;
                        double sampleConverted = sampleAmount * rateValue;

                        sb.append("1 ").append(base)
                                .append(" = ").append(String.format("%.4f", rateValue))
                                .append(" ").append(target).append("\n");
                        sb.append("📌 直接换算写法：金额 × 汇率 = 目标金额\n");
                        sb.append("💵 换算示例：")
                                .append(String.format("%.0f", sampleAmount)).append(" ")
                                .append(base).append(" × ")
                                .append(String.format("%.4f", rateValue)).append(" = ")
                                .append(String.format("%.2f", sampleConverted)).append(" ")
                                .append(target);
                    } else {
                        sb.append("❌ 未找到货币代码：").append(target).append("\n");
                        sb.append("💡 可用货币代码：USD, EUR, JPY, GBP, THB, KRW, SGD, AUD 等");
                    }
                } else {
                    String[] common = {"USD", "EUR", "JPY", "GBP", "THB", "KRW", "SGD", "AUD", "HKD"};
                    for (String code : common) {
                        JsonNode rate = rates.get(code);
                        if (rate != null) {
                            sb.append("  ").append(code).append("：")
                                    .append(String.format("%.4f", rate.asDouble())).append("\n");
                        }
                    }
                    sb.append("\n💡 需要查询其他货币，请指定 targetCurrency 参数");
                }

                log.info("[CurrencyTool] 查询成功: base={}, target={}", baseCurrency, targetCurrency);
                return sb.toString();
            }
        } catch (Exception e) {
            log.error("[CurrencyTool] 查询失败: base={}", baseCurrency, e);
            return "❌ 汇率查询失败：" + e.getMessage() + "。请稍后再试。";
        }
    }
}
