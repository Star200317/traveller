package com.travel.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * 工具：百度翻译API（免费200万字符/月）
 * 用途：为出境游用户提供多语言翻译辅助
 */
@Slf4j
@Component
public class BaiduTranslateTool {

    @Value("${baidu.translate.appid:}")
    private String appId;

    @Value("${baidu.translate.secret:}")
    private String secretKey;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(name = "translate_text", description = """
            多语言翻译工具，支持200+语言互译。
            免费额度：200万字符/月（足够个人使用）

            【重要】此工具仅适用于【出境游】场景！

            调用时机：
            - 用户询问外语菜单、路牌、指示牌的意思
            - 出境游需要翻译对话（如"谢谢用日语怎么说？"）
            - 需要将中文景点介绍翻译成英文/日文等

            参数说明：
            - text：待翻译文本（必填）
            - from：源语言代码（可选，默认auto自动检测）
            - to：目标语言代码（必填）

            常用语言代码：
            - zh = 中文
            - en = 英语
            - ja = 日语
            - ko = 韩语
            - fr = 法语
            - th = 泰语

            示例：
            - 中文→英语：translate_text(text="你好", from="zh", to="en")
            - 自动检测→日语：translate_text(text="Thank you", from="auto", to="ja")
            """)
    public String translate(
            @ToolParam(description = "待翻译文本") String text,
            @ToolParam(description = "源语言代码（可选，默认auto自动检测）", required = false) String from,
            @ToolParam(description = "目标语言代码（必填），如 en=英语, ja=日语, ko=韩语, th=泰语") String to) {

        if (appId == null || appId.trim().isEmpty() || secretKey == null || secretKey.trim().isEmpty()) {
            return "❌ 百度翻译API未配置！请在application.yml中配置 baidu.translate.appid 和 baidu.translate.secret\n" +
                    "注册地址：https://api.fanyi.baidu.com/";
        }

        if (text == null || text.trim().isEmpty()) {
            return "❌ 待翻译文本不能为空！";
        }

        if (to == null || to.trim().isEmpty()) {
            return "❌ 目标语言代码不能为空！常用代码：en=英语, ja=日语, ko=韩语, th=泰语";
        }

        try {
            String sourceLang = (from != null && !from.trim().isEmpty()) ? from.trim() : "auto";
            String targetLang = to.trim();

            // 生成随机salt
            String salt = String.valueOf(new Random().nextInt(100000));

            // 构造签名：appid + text + salt + secretKey 的MD5
            String signStr = appId + text + salt + secretKey;
            String sign = md5(signStr);

            // 构造请求URL
            String url = String.format(
                    "https://fanyi-api.baidu.com/api/trans/vip/translate?q=%s&from=%s&to=%s&appid=%s&salt=%s&sign=%s",
                    URLEncoder.encode(text, StandardCharsets.UTF_8),
                    sourceLang,
                    targetLang,
                    appId,
                    salt,
                    sign
            );

            Request request = new Request.Builder().url(url).build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return String.format("❌ 翻译失败，HTTP状态码：%d", response.code());
                }

                String body = response.body().string();
                JsonNode json = objectMapper.readTree(body);

                // 检查错误
                if (json.has("error_code")) {
                    String errorCode = json.get("error_code").asText();
                    String errorMsg = json.has("error_msg") ? json.get("error_msg").asText() : "";
                    return String.format("❌ 翻译失败（%s）：%s", errorCode, errorMsg);
                }

                // 提取翻译结果
                JsonNode transResult = json.path("trans_result");
                if (!transResult.isArray() || transResult.isEmpty()) {
                    return "❌ 翻译结果为空！";
                }

                StringBuilder sb = new StringBuilder();
                String fromLangName = getLanguageName(sourceLang);
                String toLangName = getLanguageName(targetLang);
                sb.append("🌐 翻译结果（").append(fromLangName).append(" → ").append(toLangName).append("）：\n\n");
                sb.append("原文：").append(text).append("\n\n");
                sb.append("译文：");

                StringBuilder translation = new StringBuilder();
                for (JsonNode item : transResult) {
                    if (translation.length() > 0) {
                        translation.append("\n");
                    }
                    translation.append(item.path("dst").asText());
                }
                sb.append(translation);

                sb.append("\n\n💡 数据来源：百度翻译API（免费200万字符/月）");
                log.info("[BaiduTranslateTool] 翻译成功: from={}, to={}, text={}", sourceLang, targetLang, text);
                return sb.toString();
            }
        } catch (Exception e) {
            log.error("[BaiduTranslateTool] 翻译失败: text={}", text, e);
            return "❌ 翻译失败：" + e.getMessage() + "。请稍后再试。";
        }
    }

    /**
     * MD5 加密
     */
    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("MD5加密失败", e);
        }
    }

    /**
     * 获取语言名称
     */
    private String getLanguageName(String code) {
        return switch (code) {
            case "zh" -> "中文";
            case "en" -> "英语";
            case "ja" -> "日语";
            case "ko" -> "韩语";
            case "fr" -> "法语";
            case "de" -> "德语";
            case "th" -> "泰语";
            case "ru" -> "俄语";
            case "es" -> "西班牙语";
            case "it" -> "意大利语";
            case "auto" -> "自动检测";
            default -> code;
        };
    }
}
