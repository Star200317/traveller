package com.travel.service;

import com.travel.common.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 查询重写与多查询扩展服务
 * 实现查询优化、查询扩展和上下文增强功能
 */
@Service
public class QueryRewriteService {

    @Autowired
    private RestTemplate restTemplate;

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    private static final String DASHSCOPE_CHAT_URL = 
        "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

    /**
     * 查询重写 - 将用户查询改写为更适合检索的形式
     * @param query 原始查询
     * @param context 上下文信息（可选）
     * @return 改写后的查询
     */
    public String rewriteQuery(String query, String context) {
        String systemPrompt = buildRewritePrompt(context);
        return callLLM(query, systemPrompt);
    }

    /**
     * 多查询扩展 - 生成多个相关查询以扩大检索范围
     * @param query 原始查询
     * @param context 上下文信息（可选）
     * @param numVariations 生成查询的数量（默认3个）
     * @return 扩展后的查询列表
     */
    public List<String> expandQueries(String query, String context, int numVariations) {
        String systemPrompt = buildExpansionPrompt(context, numVariations);
        String response = callLLM(query, systemPrompt);
        return parseExpandedQueries(response, numVariations);
    }

    /**
     * 查询增强 - 结合上下文进行查询优化
     * @param query 原始查询
     * @param conversationHistory 对话历史
     * @param userPreferences 用户偏好
     * @return 增强后的查询
     */
    public String enhanceQuery(String query, String conversationHistory, String userPreferences) {
        String context = buildContextString(conversationHistory, userPreferences);
        return rewriteQuery(query, context);
    }

    /**
     * 批量查询扩展 - 对多个查询进行扩展并去重
     * @param queries 查询列表
     * @param context 上下文
     * @return 扩展后的唯一查询集合
     */
    public Set<String> batchExpandQueries(List<String> queries, String context) {
        Set<String> expandedQueries = new LinkedHashSet<>();
        
        for (String query : queries) {
            // 保留原查询
            expandedQueries.add(query);
            
            // 生成扩展查询
            List<String> expansions = expandQueries(query, context, 2);
            expandedQueries.addAll(expansions);
        }
        
        return expandedQueries;
    }

    /**
     * 查询意图识别 - 识别用户查询的意图类型
     * @param query 查询文本
     * @return 意图类型（attraction/hotel/food/transport/general）
     */
    public String recognizeIntent(String query) {
        String systemPrompt = "你是一个旅游助手，请识别用户查询的意图类型。" +
                            "只返回一个词：attraction(景点相关)、hotel(酒店相关)、" +
                            "food(美食相关)、transport(交通相关)、general(一般问题)。" +
                            "不要解释，直接返回意图类型。";
        
        String result = callLLM(query, systemPrompt);
        return result.trim().toLowerCase();
    }

    /**
     * 构建重写提示词
     */
    private String buildRewritePrompt(String context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个专业的旅游查询优化助手。你的任务是将用户的查询改写为更适合信息检索的形式。\n\n");
        prompt.append("改写原则：\n");
        prompt.append("1. 保留原始查询的核心意图\n");
        prompt.append("2. 扩展同义词和相关概念\n");
        prompt.append("3. 使用更正式、完整的表达\n");
        prompt.append("4. 添加必要的上下文信息\n");
        prompt.append("5. 分解复合查询为简单查询\n\n");
        
        if (context != null && !context.isEmpty()) {
            prompt.append("当前上下文信息：\n").append(context).append("\n\n");
        }
        
        prompt.append("请直接输出改写后的查询，不要解释。");
        
        return prompt.toString();
    }

    /**
     * 构建扩展提示词
     */
    private String buildExpansionPrompt(String context, int numVariations) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个专业的旅游查询扩展助手。你的任务是根据用户查询生成多个相关变体查询。\n\n");
        prompt.append("扩展原则：\n");
        prompt.append("1. 从不同角度和层面扩展查询\n");
        prompt.append("2. 使用同义词、相关概念进行改写\n");
        prompt.append("3. 考虑不同的表达方式和措辞\n");
        prompt.append("4. 添加必要的限定词（地点、时间等）\n");
        prompt.append("5. 确保每个变体都是独立的、完整的查询\n\n");
        
        if (context != null && !context.isEmpty()) {
            prompt.append("当前上下文信息：\n").append(context).append("\n\n");
        }
        
        prompt.append("请生成 ").append(numVariations).append(" 个不同的查询变体，");
        prompt.append("每个变体一行，只输出查询，不要编号或解释。");
        
        return prompt.toString();
    }

    /**
     * 构建上下文字符串
     */
    private String buildContextString(String conversationHistory, String userPreferences) {
        StringBuilder context = new StringBuilder();
        
        if (conversationHistory != null && !conversationHistory.isEmpty()) {
            context.append("【对话历史】\n").append(conversationHistory).append("\n\n");
        }
        
        if (userPreferences != null && !userPreferences.isEmpty()) {
            context.append("【用户偏好】\n").append(userPreferences).append("\n\n");
        }
        
        return context.toString();
    }

    /**
     * 解析扩展查询结果
     */
    private List<String> parseExpandedQueries(String response, int numVariations) {
        List<String> queries = new ArrayList<>();
        
        if (response == null || response.trim().isEmpty()) {
            return queries;
        }
        
        // 按行分割
        String[] lines = response.split("\n");
        for (String line : lines) {
            line = line.trim();
            // 过滤空行和纯数字
            if (!line.isEmpty() && !line.matches("^\\d+\\.?$")) {
                // 移除可能的序号（1. 2. 等）
                line = line.replaceAll("^\\d+[.、]\\s*", "");
                if (!line.isEmpty()) {
                    queries.add(line);
                }
            }
        }
        
        // 如果解析结果太少，返回原内容作为单条
        if (queries.isEmpty() && !response.trim().isEmpty()) {
            queries.add(response.trim());
        }
        
        return queries;
    }

    /**
     * 调用LLM API
     */
    private String callLLM(String query, String systemPrompt) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "qwen-max");
            
            List<Map<String, String>> messages = new ArrayList<>();
            
            // 系统消息
            Map<String, String> systemMessage = new HashMap<>();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemPrompt);
            messages.add(systemMessage);
            
            // 用户消息
            Map<String, String> userMessage = new HashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", query);
            messages.add(userMessage);
            
            requestBody.put("messages", messages);
            requestBody.put("max_tokens", 2000);
            requestBody.put("temperature", 0.7);
            
            // 构建请求头
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("Authorization", "Bearer " + apiKey);
            headers.set("Content-Type", "application/json");
            
            org.springframework.http.HttpEntity<Map<String, Object>> request = 
                new org.springframework.http.HttpEntity<>(requestBody, headers);
            
            // 发送请求
            String apiUrl = DASHSCOPE_CHAT_URL;
            org.springframework.http.ResponseEntity<String> response = 
                restTemplate.postForEntity(apiUrl, request, String.class);
            
            // 解析响应
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return extractContentFromResponse(response.getBody());
            }
            
        } catch (Exception e) {
            System.err.println("LLM API调用失败: " + e.getMessage());
            e.printStackTrace();
        }
        
        // 失败时返回原查询
        return query;
    }

    /**
     * 从API响应中提取内容
     */
    private String extractContentFromResponse(String responseBody) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = 
                new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(responseBody);
            
            // 提取 choices[0].message.content
            com.fasterxml.jackson.databind.JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                com.fasterxml.jackson.databind.JsonNode message = choices.get(0).get("message");
                if (message != null) {
                    com.fasterxml.jackson.databind.JsonNode content = message.get("content");
                    if (content != null) {
                        return content.asText();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("解析LLM响应失败: " + e.getMessage());
        }
        return "";
    }
}
