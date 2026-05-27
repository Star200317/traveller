package com.travel.service;

import lombok.Data;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 查询结果融合服务
 * 用于整合多个查询扩展的检索结果，进行加权融合排序
 */
@Service
public class QueryFusionService {

    /**
     * 查询结果项
     */
    @Data
    public static class FusionResult {
        private String text;
        private String title;
        private String category;
        private String docId;
        private double score;
        private String sourceQuery;  // 来源查询
        private Map<String, Object> metadata;

        public FusionResult() {}

    public FusionResult(String text, double score) {
        this.text = text;
        this.score = score;
    }

    /**
     * 拷贝构造函数（用于深拷贝）
     */
    public FusionResult(FusionResult other) {
        this.text = other.text;
        this.title = other.title;
        this.category = other.category;
        this.docId = other.docId;
        this.score = other.score;
        this.sourceQuery = other.sourceQuery;
        this.metadata = other.metadata != null ? new HashMap<>(other.metadata) : null;
    }
}

    /**
     * 加权融合多个查询的检索结果
     * 使用和改进的RRF（Reciprocal Rank Fusion）算法
     * 
     * @param resultsPerQuery 每个查询的检索结果列表
     * @param k RRF算法中的常数（通常设为60）
     * @return 融合后的结果列表
     */
    public List<FusionResult> fuseResults(Map<String, List<FusionResult>> resultsPerQuery, int k) {
        if (resultsPerQuery == null || resultsPerQuery.isEmpty()) {
            return new ArrayList<>();
        }

        // 计算每个结果的融合分数
        Map<String, Double> fusionScores = new HashMap<>();
        Map<String, FusionResult> resultMap = new HashMap<>();

        for (Map.Entry<String, List<FusionResult>> entry : resultsPerQuery.entrySet()) {
            String query = entry.getKey();
            List<FusionResult> results = entry.getValue();

            // 按排名分配分数（RRF算法）
            for (int i = 0; i < results.size(); i++) {
                FusionResult result = results.get(i);
                String key = generateResultKey(result);

                // RRF分数 = 1 / (k + rank)
                double rrfScore = 1.0 / (k + i + 1);

                // 加上原始相似度分数的权重
                double combinedScore = rrfScore + (result.getScore() * 0.1);

                // 累加所有查询的分数
                fusionScores.merge(key, combinedScore, Double::sum);
                resultMap.put(key, result);
            }
        }

        // 按融合分数排序
        List<Map.Entry<String, Double>> sortedEntries = fusionScores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .collect(Collectors.toList());

        // 构建融合结果列表
        List<FusionResult> fusedResults = new ArrayList<>();
        for (Map.Entry<String, Double> entry : sortedEntries) {
            FusionResult result = new FusionResult(resultMap.get(entry.getKey()));
            result.setScore(entry.getValue());
            fusedResults.add(result);
        }

        return fusedResults;
    }

    /**
     * 简单的结果去重（基于文本相似度）
     * 
     * @param results 结果列表
     * @param similarityThreshold 相似度阈值（0-1）
     * @return 去重后的结果
     */
    public List<FusionResult> deduplicate(List<FusionResult> results, double similarityThreshold) {
        if (results == null || results.isEmpty()) {
            return new ArrayList<>();
        }

        List<FusionResult> deduplicated = new ArrayList<>();

        for (FusionResult candidate : results) {
            boolean isDuplicate = false;

            for (FusionResult existing : deduplicated) {
                double similarity = calculateTextSimilarity(
                    candidate.getText(), 
                    existing.getText()
                );

                if (similarity >= similarityThreshold) {
                    isDuplicate = true;
                    // 如果发现重复，保留分数更高的
                    if (candidate.getScore() > existing.getScore()) {
                        deduplicated.remove(existing);
                        deduplicated.add(candidate);
                    }
                    break;
                }
            }

            if (!isDuplicate) {
                deduplicated.add(candidate);
            }
        }

        return deduplicated;
    }

    /**
     * 按类别分组结果
     * 
     * @param results 结果列表
     * @return 按类别分组的结果
     */
    public Map<String, List<FusionResult>> groupByCategory(List<FusionResult> results) {
        return results.stream()
            .collect(Collectors.groupingBy(
                r -> r.getCategory() != null ? r.getCategory() : "general"
            ));
    }

    /**
     * 生成结果的唯一标识键
     */
    private String generateResultKey(FusionResult result) {
        // 使用文本前100个字符作为标识
        String textPrefix = result.getText();
        if (textPrefix.length() > 100) {
            textPrefix = textPrefix.substring(0, 100);
        }
        return textPrefix + "|" + (result.getTitle() != null ? result.getTitle() : "");
    }

    /**
     * 计算两个文本的相似度（简单实现）
     * 使用Jaccard相似系数
     */
    private double calculateTextSimilarity(String text1, String text2) {
        if (text1 == null || text2 == null) {
            return 0.0;
        }

        // 分词
        Set<String> words1 = new HashSet<>(Arrays.asList(text1.split("\\s+")));
        Set<String> words2 = new HashSet<>(Arrays.asList(text2.split("\\s+")));

        // 计算Jaccard相似度
        Set<String> intersection = new HashSet<>(words1);
        intersection.retainAll(words2);

        Set<String> union = new HashSet<>(words1);
        union.addAll(words2);

        if (union.isEmpty()) {
            return 0.0;
        }

        return (double) intersection.size() / union.size();
    }

    /**
     * 从Pinecone查询结果转换为FusionResult
     */
    public List<FusionResult> convertFromPineconeResults(List<Map<String, Object>> pineconeResults) {
        List<FusionResult> results = new ArrayList<>();

        for (Map<String, Object> pineconeResult : pineconeResults) {
            FusionResult result = new FusionResult();
            
            // 提取文本
            if (pineconeResult.containsKey("text")) {
                result.setText(String.valueOf(pineconeResult.get("text")));
            }
            
            // 提取分数
            if (pineconeResult.containsKey("score")) {
                result.setScore(((Number) pineconeResult.get("score")).doubleValue());
            } else if (pineconeResult.containsKey("score")) {
                result.setScore(0.0);
            }
            
            // 提取元数据
            if (pineconeResult.containsKey("title")) {
                result.setTitle(String.valueOf(pineconeResult.get("title")));
            }
            if (pineconeResult.containsKey("category")) {
                result.setCategory(String.valueOf(pineconeResult.get("category")));
            }
            if (pineconeResult.containsKey("docId")) {
                result.setDocId(String.valueOf(pineconeResult.get("docId")));
            }

            results.add(result);
        }

        return results;
    }
}
