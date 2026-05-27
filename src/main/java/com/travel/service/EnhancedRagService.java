package com.travel.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.protobuf.Struct;
import com.travel.entity.KnowledgeDoc;
import com.travel.mapper.KnowledgeDocMapper;
import io.pinecone.clients.Index;
import io.pinecone.clients.Pinecone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 增强版RAG服务：支持查询重写、多查询扩展、结果融合
 *
 * 新增功能：
 * 1. 查询重写（Query Rewrite）
 * 2. 多查询扩展（Multi-Query Expansion）
 * 3. 上下文增强查询
 * 4. RRF结果融合（Reciprocal Rank Fusion）
 * 5. 意图识别
 *
 * @author Travel System
 * @version 3.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnhancedRagService extends ServiceImpl<KnowledgeDocMapper, KnowledgeDoc> {

    // ==================== 依赖注入 ====================

    private final Pinecone pineconeClient;

    // 注入缓存的Index连接，避免每次查询重新创建
    private final Index pineconeIndex;

    // 注入查询重写服务
    @Autowired(required = false)
    private QueryRewriteService queryRewriteService;

    // 注入结果融合服务
    @Autowired(required = false)
    private QueryFusionService queryFusionService;

    // RestTemplate 直接实例化
    private RestTemplate restTemplate = new RestTemplate();

    // ==================== 配置参数 ====================

    @Value("${spring.ai.dashscope.api-key}")
    private String dashscopeApiKey;

    @Value("${spring.ai.dashscope.embedding.model:text-embedding-v3}")
    private String embeddingModel;

    @Value("${spring.ai.dashscope.embedding.dimension:1024}")
    private int embeddingDim;

    @Value("${pinecone.index-name}")
    private String indexName;

    // 命名空间配置
    @Value("${pinecone.namespace.attractions:attractions}")
    private String nsAttractions;

    @Value("${pinecone.namespace.cities:cities}")
    private String nsCities;

    @Value("${pinecone.namespace.tips:travel-tips}")
    private String nsTips;

    @Value("${pinecone.namespace.hotels:hotels}")
    private String nsHotels;

    @Value("${pinecone.namespace.food:food}")
    private String nsFood;

    @Value("${pinecone.namespace.user-prefix:user-}")
    private String nsUserPrefix;

    // ==================== 新增配置 ====================

    // 查询扩展配置
    @Value("${rag.query-rewrite.enabled:false}")
    private boolean queryRewriteEnabled;

    @Value("${rag.multi-query.enabled:false}")
    private boolean multiQueryEnabled;

    @Value("${rag.multi-query.expansion-count:3}")
    private int expansionCount;

    @Value("${rag.fusion.k:60}")
    private int fusionK;

    // ==================== 常量 ====================

    private static final int EMBEDDING_MAX_LENGTH = 8000;
    private static final int DEFAULT_CHUNK_SIZE = 1000;
    private static final int DEFAULT_OVERLAP = 200;
    private static final double SIMILARITY_THRESHOLD = 0.5;

    private static final String DASHSCOPE_EMBEDDING_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";

    // ==================== 增强检索方法 ====================

    /**
     * 增强检索 - 集成查询重写和扩展
     *
     * 完整流程：
     * 1. 意图识别（可选）
     * 2. 查询重写（可选）
     * 3. 多查询扩展（可选）
     * 4. 并行向量检索
     * 5. 结果融合
     * 6. 去重和过滤
     *
     * @param query 原始查询
     * @param category 分类
     * @param userId 用户ID
     * @param topK 返回结果数
     * @param useEnhanced 是否使用增强功能
     * @return 检索结果
     */
    public String enhancedQuery(String query, String category, Long userId, int topK, boolean useEnhanced) {
        log.info("[EnhancedRAG] 开始增强检索: query={}, category={}, useEnhanced={}",
                query, category, useEnhanced);

        try {
            String processedQuery = query;
            List<String> expandedQueries = new ArrayList<>();

            // 步骤1：查询重写（如果启用）
            if (useEnhanced && queryRewriteEnabled && queryRewriteService != null) {
                processedQuery = queryRewriteService.rewriteQuery(query, null);
                log.info("[EnhancedRAG] 查询重写完成: {} -> {}", query, processedQuery);
            }

            // 步骤2：多查询扩展（如果启用）
            if (useEnhanced && multiQueryEnabled && queryRewriteService != null) {
                expandedQueries = queryRewriteService.expandQueries(
                    processedQuery, null, expansionCount);
                log.info("[EnhancedRAG] 查询扩展完成: 生成了 {} 个变体查询", expandedQueries.size());
            }

            // 构建查询列表
            List<String> allQueries = new ArrayList<>();
            allQueries.add(processedQuery);  // 原始/重写后的查询
            allQueries.addAll(expandedQueries);  // 扩展的查询

            // 步骤3：并行检索
            Map<String, List<String>> resultsPerQuery = new HashMap<>();
            for (String q : allQueries) {
                String results = basicQuery(q, category, userId, topK);
                if (!results.isEmpty() && !"知识库中暂无相关信息".equals(results)) {
                    resultsPerQuery.put(q, splitResults(results));
                }
            }

            // 步骤4：结果融合（如果有多个查询结果）
            if (queryFusionService != null && !resultsPerQuery.isEmpty()) {
                return fuseAndFormatResults(resultsPerQuery, topK);
            }

            // 如果没有启用融合或只有单个查询，直接返回原始结果
            return processedQuery.equals(query)
                    ? basicQuery(query, category, userId, topK)
                    : basicQuery(processedQuery, category, userId, topK);

        } catch (Exception e) {
            log.error("[EnhancedRAG] 增强检索失败，回退到基础检索", e);
            return basicQuery(query, category, userId, topK);
        }
    }

    /**
     * 上下文增强检索 - 结合对话历史和用户偏好
     *
     * @param query 原始查询
     * @param category 分类
     * @param userId 用户ID
     * @param topK 返回结果数
     * @param conversationHistory 对话历史
     * @param userPreferences 用户偏好
     * @return 检索结果
     */
    public String contextualQuery(String query, String category, Long userId, int topK,
                                   String conversationHistory, String userPreferences) {
        log.info("[EnhancedRAG] 开始上下文增强检索");

        try {
            // 使用QueryRewriteService进行上下文增强
            if (queryRewriteService != null) {
                String enhancedQuery = queryRewriteService.enhanceQuery(
                    query, conversationHistory, userPreferences);

                log.info("[EnhancedRAG] 上下文增强完成: {} -> {}", query, enhancedQuery);

                // 识别查询意图
                String intent = queryRewriteService.recognizeIntent(query);
                log.info("[EnhancedRAG] 识别到意图: {}", intent);

                // 如果识别到特定意图，可以调整category
                String adjustedCategory = adjustCategoryByIntent(category, intent);

                return basicQuery(enhancedQuery, adjustedCategory, userId, topK);
            }

            // 如果服务不可用，回退到基础检索
            return basicQuery(query, category, userId, topK);

        } catch (Exception e) {
            log.error("[EnhancedRAG] 上下文检索失败，回退到基础检索", e);
            return basicQuery(query, category, userId, topK);
        }
    }

    /**
     * 意图识别并调整查询
     *
     * @param query 用户查询
     * @param category 当前分类
     * @param userId 用户ID
     * @param topK 返回结果数
     * @return 检索结果
     */
    public String intentAwareQuery(String query, String category, Long userId, int topK) {
        log.info("[EnhancedRAG] 开始意图感知检索");

        try {
            if (queryRewriteService == null) {
                return basicQuery(query, category, userId, topK);
            }

            // 识别查询意图
            String intent = queryRewriteService.recognizeIntent(query);
            log.info("[EnhancedRAG] 识别的意图: {}", intent);

            // 根据意图调整查询
            String adjustedQuery = query;

            // 如果意图与当前category不匹配，进行查询扩展
            if (!isIntentMatchCategory(intent, category)) {
                // 生成意图相关的扩展查询
                List<String> intentQueries = queryRewriteService.expandQueries(query, null, 2);
                adjustedQuery = intentQueries.isEmpty() ? query : intentQueries.get(0);
            }

            // 根据意图调整category
            String adjustedCategory = adjustCategoryByIntent(category, intent);

            // 执行检索
            return basicQuery(adjustedQuery, adjustedCategory, userId, topK);

        } catch (Exception e) {
            log.error("[EnhancedRAG] 意图感知检索失败", e);
            return basicQuery(query, category, userId, topK);
        }
    }

    // ==================== 原有公开方法（保留） ====================

    /**
     * 向量化文档并存储到Pinecone（原有方法）
     */
    public void indexDocument(KnowledgeDoc doc) {
        log.info("[向量化处理] ==========================================");
        log.info("[向量化处理] 开始处理文档: docId={}, title={}, category={}",
                doc.getId(), doc.getTitle(), doc.getCategory());
        log.info("[向量化处理] 文档内容长度: {} 字符", doc.getContent() != null ? doc.getContent().length() : 0);

        // 1. 文档分块
        log.info("[向量化处理] 步骤1/4: 文档分块...");
        List<String> chunks = splitIntoChunks(doc.getContent(), DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP);
        log.info("[向量化处理] 分块完成: 共 {} 个块 (每块约 {} 字符, 重叠 {} 字符)",
                chunks.size(), DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP);

        // 2. 确定命名空间
        String namespace = resolveNamespace(doc.getCategory(), doc.getUserId());
        log.info("[向量化处理] 步骤2/4: 确定存储位置: namespace='{}'", namespace);

        // 3. 逐块向量化
        log.info("[向量化处理] 步骤3/4: 开始逐块向量化 (调用DashScope API)...");
        List<String> pineconeIds = new ArrayList<>();
        Index index = getIndex();
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            String chunkId = doc.getId() + "_chunk_" + i;

            log.info("[向量化处理]   处理第 {}/{} 块: chunkId={}, 长度={} 字符",
                    i + 1, chunks.size(), chunkId, chunk.length());

            // 调用DashScope生成向量
            long embedStart = System.currentTimeMillis();
            float[] embedding = dashscopeEmbed(chunk);
            long embedTime = System.currentTimeMillis() - embedStart;
            List<Float> vector = floatsToList(embedding);

            log.info("[向量化处理]     向量生成完成: 维度={}, 耗时={}ms", embedding.length, embedTime);

            // 构建元数据
            Struct.Builder structBuilder = Struct.newBuilder();
            structBuilder.putFields("docId", com.google.protobuf.Value.newBuilder().setStringValue(String.valueOf(doc.getId())).build());
            structBuilder.putFields("title", com.google.protobuf.Value.newBuilder().setStringValue(doc.getTitle() != null ? doc.getTitle() : "").build());
            structBuilder.putFields("category", com.google.protobuf.Value.newBuilder().setStringValue(doc.getCategory() != null ? doc.getCategory() : "").build());
            structBuilder.putFields("chunkIndex", com.google.protobuf.Value.newBuilder().setNumberValue(i).build());
            structBuilder.putFields("text", com.google.protobuf.Value.newBuilder().setStringValue(chunk).build());
            Struct metadata = structBuilder.build();

            // 存储到Pinecone
            long upsertStart = System.currentTimeMillis();
            index.upsert(chunkId, vector, null, null, metadata, namespace);
            long upsertTime = System.currentTimeMillis() - upsertStart;

            pineconeIds.add(chunkId);
            log.info("[向量化处理]     存储到Pinecone完成: 耗时={}ms", upsertTime);
        }

        long totalTime = System.currentTimeMillis() - startTime;
        log.info("[向量化处理] 步骤4/4: 全部向量生成并存储完成");

        // 4. 更新文档状态
        doc.setPineconeIds(pineconeIds);
        doc.setStatus(1);
        updateById(doc);

        log.info("[向量化处理] ==========================================");
        log.info("[向量化处理] 文档向量化完成! docId={}, 总块数={}, namespace={}, 总耗时={}ms",
                doc.getId(), chunks.size(), namespace, totalTime);
        log.info("[向量化处理] ==========================================");
    }

    /**
     * 基础检索（原有方法，保留）
     */
    public String query(String query, String category, Long userId, int topK) {
        return basicQuery(query, category, userId, Math.max(1, Math.min(topK, 5)));
    }

    /**
     * 删除文档向量
     */
    public void deleteDocument(KnowledgeDoc doc) {
        if (doc.getPineconeIds() == null || doc.getPineconeIds().isEmpty()) {
            log.warn("[EnhancedRAG] 文档无向量ID: docId={}", doc.getId());
            return;
        }

        String namespace = resolveNamespace(doc.getCategory(), doc.getUserId());

        try {
            getIndex().deleteByIds(doc.getPineconeIds(), namespace);
            log.info("[EnhancedRAG] 文档向量删除成功: docId={}, namespace={}, vectors={}",
                    doc.getId(), namespace, doc.getPineconeIds().size());
        } catch (Exception e) {
            log.error("[EnhancedRAG] 文档向量删除失败: docId={}", doc.getId(), e);
            throw new RuntimeException("删除向量失败", e);
        }
    }

    /**
     * 批量向量化文档
     */
    public void batchIndexDocuments(List<KnowledgeDoc> docs) {
        log.info("[向量化处理] ==========================================");
        log.info("[向量化处理] 开始批量向量化: 共 {} 个文档", docs.size());
        log.info("[向量化处理] ==========================================");

        int success = 0;
        int failed = 0;
        long batchStart = System.currentTimeMillis();

        for (int i = 0; i < docs.size(); i++) {
            KnowledgeDoc doc = docs.get(i);
            log.info("[向量化处理] 批量处理进度: {}/{} - docId={}, title={}",
                    i + 1, docs.size(), doc.getId(), doc.getTitle());

            try {
                indexDocument(doc);
                success++;
                log.info("[向量化处理] 文档处理成功: docId={}", doc.getId());
            } catch (Exception e) {
                failed++;
                log.error("[向量化处理] 文档处理失败: docId={}, error={}", doc.getId(), e.getMessage(), e);
            }

            if (i < docs.size() - 1) {
                log.info("[向量化处理] ---");
            }
        }

        long batchTime = System.currentTimeMillis() - batchStart;
        log.info("[向量化处理] ==========================================");
        log.info("[向量化处理] 批量向量化完成! 总计: {}个, 成功: {}个, 失败: {}个, 总耗时: {}ms",
                docs.size(), success, failed, batchTime);
        log.info("[向量化处理] ==========================================");
    }

    // ==================== 私有方法 ====================

    /**
     * 基础检索实现
     */
    private String basicQuery(String query, String category, Long userId, int topK) {
        log.info("[RAG检索] ==========================================");
        log.info("[RAG检索] 开始检索: query='{}', category={}, userId={}, topK={}",
                query, category, userId, topK);

        try {
            // 1. 查询向量化
            log.info("[RAG检索] 步骤1/3: 将查询文本向量化...");
            long embedStart = System.currentTimeMillis();
            float[] queryEmbedding = dashscopeEmbed(query);
            long embedTime = System.currentTimeMillis() - embedStart;
            List<Float> queryVector = floatsToList(queryEmbedding);
            log.info("[RAG检索] 查询向量化完成: 维度={}, 耗时={}ms", queryEmbedding.length, embedTime);

            // 2. 确定命名空间
            List<String> namespaces = resolveQueryNamespaces(category, userId);
            String categoryDesc = getCategoryDescription(category);
            log.info("[RAG检索] 步骤2/3: 确定查询范围: category={}({}), namespaces={}", category, categoryDesc, namespaces);

            // 3. 并行查询所有命名空间
            log.info("[RAG检索] 步骤3/3: 并行查询Pinecone向量库...");
            StringBuilder result = new StringBuilder();
            int totalResults = queryNamespacesInParallel(queryVector, namespaces, topK, result);

            log.info("[RAG检索] ==========================================");
            if (totalResults == 0) {
                log.info("[RAG检索] 检索完成: 未找到相关信息");
                return "知识库中暂无相关信息";
            }

            log.info("[RAG检索] 检索完成: 找到 {} 条相关结果", totalResults);
            log.info("[RAG检索] ==========================================");
            return result.toString();

        } catch (Exception e) {
            log.error("[RAG检索] 检索失败: query={}, error={}", query, e.getMessage(), e);
            return "知识库查询失败：" + e.getMessage();
        }
    }

    /**
     * 融合多个查询的结果
     */
    private String fuseAndFormatResults(Map<String, List<String>> resultsPerQuery, int topK) {
        if (queryFusionService == null || resultsPerQuery.isEmpty()) {
            return "";
        }

        // 将字符串结果转换为FusionResult
        Map<String, List<QueryFusionService.FusionResult>> fusionInput = new HashMap<>();

        for (Map.Entry<String, List<String>> entry : resultsPerQuery.entrySet()) {
            String query = entry.getKey();
            List<String> results = entry.getValue();
            List<QueryFusionService.FusionResult> fusionResults = new ArrayList<>();

            for (String result : results) {
                QueryFusionService.FusionResult fr = parseResultString(result);
                fr.setSourceQuery(query);
                fusionResults.add(fr);
            }

            fusionInput.put(query, fusionResults);
        }

        // 执行RRF融合
        List<QueryFusionService.FusionResult> fusedResults = queryFusionService.fuseResults(fusionInput, fusionK);

        // 去重
        fusedResults = queryFusionService.deduplicate(fusedResults, 0.8);

        // 截取topK
        if (fusedResults.size() > topK) {
            fusedResults = fusedResults.subList(0, topK);
        }

        // 格式化输出
        return formatFusionResults(fusedResults);
    }

    /**
     * 解析单个结果字符串
     */
    private QueryFusionService.FusionResult parseResultString(String resultStr) {
        QueryFusionService.FusionResult result = new QueryFusionService.FusionResult();

        try {
            // 解析标题和相似度
            String title = "";
            double score = 0.0;
            String text = resultStr;

            // 提取标题
            if (resultStr.contains("【") && resultStr.contains("】")) {
                int start = resultStr.indexOf("【") + 1;
                int end = resultStr.indexOf("】");
                if (start < end) {
                    title = resultStr.substring(start, end);
                    text = resultStr.substring(end + 1).trim();
                }
            }

            // 提取相似度
            if (resultStr.contains("相似度:")) {
                int scoreStart = resultStr.indexOf("相似度:") + 4;
                int scoreEnd = resultStr.indexOf(")", scoreStart);
                if (scoreEnd > scoreStart) {
                    String scoreStr = resultStr.substring(scoreStart, scoreEnd).trim();
                    score = Double.parseDouble(scoreStr);
                }
            }

            // 移除警告文本
            text = text.replace("⚠️【地址未精确到门牌号】此结果中的地址缺少门牌号，请在输出前调用 web_search 搜索精确地址！\n", "");

            result.setTitle(title);
            result.setScore(score);
            result.setText(text);

        } catch (Exception e) {
            log.warn("[EnhancedRAG] 解析结果字符串失败: {}", e.getMessage());
            result.setText(resultStr);
        }

        return result;
    }

    /**
     * 格式化融合结果
     */
    private String formatFusionResults(List<QueryFusionService.FusionResult> results) {
        StringBuilder formatted = new StringBuilder();

        for (int i = 0; i < results.size(); i++) {
            QueryFusionService.FusionResult r = results.get(i);
            formatted.append("【").append(r.getTitle()).append("】")
                     .append(" (融合分数: ").append(String.format("%.2f", r.getScore())).append(")\n");

            // 地址精度检测
            if (!hasExactAddress(r.getText())) {
                formatted.append("⚠️【地址未精确到门牌号】此结果中的地址缺少门牌号，请在输出前调用 web_search 搜索精确地址！\n");
            }

            formatted.append(r.getText()).append("\n\n");
        }

        return formatted.toString();
    }

    /**
     * 分割检索结果
     */
    private List<String> splitResults(String results) {
        List<String> resultList = new ArrayList<>();
        if (results == null || results.isEmpty()) {
            return resultList;
        }

        // 按【】分割（每个结果以【标题】开头）
        String[] parts = results.split("\n\n");
        StringBuilder current = new StringBuilder();

        for (String part : parts) {
            if (part.contains("【") && current.length() > 0) {
                // 新结果开始，保存当前结果
                resultList.add(current.toString().trim());
                current = new StringBuilder();
            }
            current.append(part).append("\n\n");
        }

        // 添加最后一个结果
        if (current.length() > 0) {
            resultList.add(current.toString().trim());
        }

        return resultList;
    }

    /**
     * 根据意图调整category
     */
    private String adjustCategoryByIntent(String currentCategory, String intent) {
        // 如果已有明确分类且意图匹配，保持不变
        if (currentCategory != null && isIntentMatchCategory(intent, currentCategory)) {
            return currentCategory;
        }

        // 根据意图调整
        return switch (intent.toLowerCase()) {
            case "attraction" -> "attractions";
            case "hotel" -> "hotels";
            case "food" -> "food";
            case "transport" -> "all";
            default -> currentCategory != null ? currentCategory : "all";
        };
    }

    /**
     * 判断意图是否匹配分类
     */
    private boolean isIntentMatchCategory(String intent, String category) {
        if (category == null || intent == null) {
            return false;
        }

        category = category.toLowerCase();
        intent = intent.toLowerCase();

        return switch (intent) {
            case "attraction" -> category.contains("attraction") || category.contains("景点");
            case "hotel" -> category.contains("hotel") || category.contains("酒店");
            case "food" -> category.contains("food") || category.contains("美食");
            case "transport" -> category.contains("transport") || category.contains("交通");
            default -> false;
        };
    }

    /**
     * 获取Pinecone索引连接
     * 优先使用缓存的Index实例，减少连接创建开销
     */
    private Index getIndex() {
        // 如果有缓存的Index实例，直接使用
        if (pineconeIndex != null) {
            return pineconeIndex;
        }
        // 降级：动态获取（兼容测试场景）
        Index index = pineconeClient.getIndexConnection(indexName);
        if (index == null) {
            throw new RuntimeException("Pinecone索引连接失败，请确认索引 [" + indexName + "] 已创建且状态为Ready");
        }
        return index;
    }

    private float[] dashscopeEmbed(String text) {
        if (text.length() > EMBEDDING_MAX_LENGTH) {
            log.warn("[向量化处理] 文本超长({}字符)，截断到{}字符", text.length(), EMBEDDING_MAX_LENGTH);
            text = text.substring(0, EMBEDDING_MAX_LENGTH);
        }

        try {
            log.debug("[向量化处理]   调用DashScope Embedding API: model={}, textLength={}字符, dimensions={}",
                    embeddingModel, text.length(), embeddingDim);

            Map<String, Object> input = new HashMap<>();
            input.put("texts", List.of(text));

            Map<String, Object> body = new HashMap<>();
            body.put("model", embeddingModel);
            body.put("input", input);
            body.put("dimensions", embeddingDim);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(dashscopeApiKey);

            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            long apiStart = System.currentTimeMillis();
            ResponseEntity<String> responseEntity = restTemplate.exchange(
                    DASHSCOPE_EMBEDDING_URL,
                    HttpMethod.POST,
                    requestEntity,
                    String.class
            );
            long apiTime = System.currentTimeMillis() - apiStart;

            String response = responseEntity.getBody();
            log.debug("[向量化处理]   DashScope API响应耗时: {}ms", apiTime);

            return parseEmbeddingResponse(response);

        } catch (Exception e) {
            log.error("[向量化处理] DashScope embedding调用失败: {}", e.getMessage(), e);
            throw new RuntimeException("向量生成失败", e);
        }
    }

    private float[] parseEmbeddingResponse(String json) {
        try {
            String target = "\"embedding\":[";
            int start = json.indexOf(target);
            if (start == -1) {
                throw new RuntimeException("无法解析embedding响应: " + json);
            }

            start += target.length();
            int end = json.indexOf("]", start);
            if (end == -1) {
                throw new RuntimeException("embedding数组格式错误: " + json);
            }

            String arrayStr = json.substring(start, end);
            String[] parts = arrayStr.split(",");

            float[] result = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                result[i] = Float.parseFloat(parts[i].trim());
            }

            log.debug("[EnhancedRAG] embedding生成成功: dimension={}", result.length);
            return result;

        } catch (Exception e) {
            log.error("[EnhancedRAG] 解析embedding响应失败: {}", json, e);
            throw new RuntimeException("解析向量失败", e);
        }
    }

    private List<Float> floatsToList(float[] array) {
        List<Float> list = new ArrayList<>(array.length);
        for (float f : array) {
            list.add(f);
        }
        return list;
    }

    private int parseAndAppendResults(Object response, StringBuilder result) {
        try {
            Method resultsMethod = findListMethod(response.getClass());
            if (resultsMethod == null) {
                log.error("[EnhancedRAG] 无法找到返回 List 的方法，response class={}", response.getClass().getName());
                return 0;
            }

            Object matchesObj = resultsMethod.invoke(response);
            if (!(matchesObj instanceof List<?> matches) || matches.isEmpty()) {
                return 0;
            }

            int count = 0;
            for (Object item : matches) {
                Method getScoreMethod = findMethod(item.getClass(), "getScore");
                if (getScoreMethod == null) continue;
                double score = ((Number) getScoreMethod.invoke(item)).doubleValue();

                if (score < SIMILARITY_THRESHOLD) {
                    continue;
                }

                Method getMetadataMethod = findMethod(item.getClass(), "getMetadata");
                if (getMetadataMethod == null) continue;
                Object metadata = getMetadataMethod.invoke(item);

                String title = "";
                String text = "";

                if (metadata != null) {
                    Method getFieldsMethod = findMethod(metadata.getClass(), "getFieldsMap");
                    if (getFieldsMethod != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, ?> fields = (Map<String, ?>) getFieldsMethod.invoke(metadata);

                        for (Map.Entry<String, ?> entry : fields.entrySet()) {
                            String key = entry.getKey();
                            Object value = entry.getValue();

                            try {
                                if ("title".equals(key)) {
                                    title = value.getClass().getMethod("getStringValue").invoke(value).toString();
                                } else if ("text".equals(key)) {
                                    text = value.getClass().getMethod("getStringValue").invoke(value).toString();
                                }
                            } catch (Exception e) {
                                log.warn("[EnhancedRAG] 解析元数据字段失败: key={}", key);
                            }
                        }
                    }
                }

                if (!text.isEmpty()) {
                    result.append("【").append(title).append("】 (相似度: ").append(String.format("%.2f", score)).append(")\n");

                    if (!hasExactAddress(text)) {
                        result.append("⚠️【地址未精确到门牌号】此结果中的地址缺少门牌号，请在输出前调用 web_search 搜索精确地址！\n");
                    }

                    result.append(text).append("\n\n");
                    count++;
                }
            }

            return count;

        } catch (Exception e) {
            log.error("[EnhancedRAG] 解析查询结果失败", e);
            return 0;
        }
    }

    private Method findListMethod(Class<?> clazz) {
        for (Method m : clazz.getMethods()) {
            if (m.getParameterCount() == 0 && List.class.isAssignableFrom(m.getReturnType())) {
                log.debug("[EnhancedRAG] 找到 List 方法: {}.{}()", clazz.getSimpleName(), m.getName());
                return m;
            }
        }
        return null;
    }

    private Method findMethod(Class<?> clazz, String name) {
        try {
            return clazz.getMethod(name);
        } catch (NoSuchMethodException e) {
            try {
                return clazz.getMethod(name.substring(0, 1).toUpperCase() + name.substring(1));
            } catch (NoSuchMethodException e2) {
                log.warn("[EnhancedRAG] 方法未找到: {}.{}()", clazz.getSimpleName(), name);
                return null;
            }
        }
    }

    private List<String> splitIntoChunks(String text, int maxLen, int overlap) {
        List<String> chunks = new ArrayList<>();

        if (text == null || text.isEmpty()) {
            return chunks;
        }

        String[] paragraphs = text.split("\\n{2,}");
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            String trimmedPara = para.trim();
            if (trimmedPara.isEmpty()) {
                continue;
            }

            if (current.length() + trimmedPara.length() > maxLen && current.length() > 0) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
            }

            if (trimmedPara.length() > maxLen) {
                if (current.length() > 0) {
                    chunks.add(current.toString().trim());
                    current = new StringBuilder();
                }
                chunks.addAll(splitOversizedChunk(trimmedPara, maxLen));
            } else {
                current.append(trimmedPara).append("\n\n");
            }
        }

        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }

        List<String> safeChunks = new ArrayList<>();
        for (String chunk : chunks) {
            if (chunk.length() <= EMBEDDING_MAX_LENGTH) {
                safeChunks.add(chunk);
            } else {
                log.warn("[EnhancedRAG] 块超长({})，强制分割", chunk.length());
                safeChunks.addAll(splitOversizedChunk(chunk, EMBEDDING_MAX_LENGTH));
            }
        }

        if (safeChunks.isEmpty()) {
            String truncated = text.substring(0, Math.min(text.length(), EMBEDDING_MAX_LENGTH));
            safeChunks.add(truncated);
        }

        return safeChunks;
    }

    private boolean hasExactAddress(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        if (text.matches(".*\\d+[号栋弄座铺].*")) {
            return true;
        }

        String[] landmarkKeywords = new String[]{
            "南普陀寺", "鼓浪屿", "厦门大学", "高崎国际机场", "火车站", "客运站", "码头",
            "故宫", "长城", "天安门", "颐和园", "圆明园", "天坛", "鸟巢", "水立方",
            "东方明珠", "外滩", "豫园", "城隍庙",
            "洱海", "古城", "苍山", "三塔", "玉龙雪山", "泸沽湖",
            "天涯海角", "蜈支洲岛", "亚龙湾", "南山寺", "大小洞天",
            "石林", "滇池", "翠湖", "九乡", "七彩云南",
            "机场", "火车站", "高铁站", "地铁站"
        };
        for (String keyword : landmarkKeywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }

        if (text.matches(".*[区市县].*[路街巷道].*\\d{2,}.*")) {
            return true;
        }

        return false;
    }

    private List<String> splitOversizedChunk(String text, int maxLen) {
        List<String> result = new ArrayList<>();

        String[] sentences = text.split("(?<=[。！？.!?])\\s*");
        StringBuilder buf = new StringBuilder();

        for (String sentence : sentences) {
            if (sentence.isEmpty()) {
                continue;
            }

            if (buf.length() + sentence.length() > maxLen && buf.length() > 0) {
                result.add(buf.toString().trim());
                buf = new StringBuilder();
            }

            buf.append(sentence);
        }

        if (buf.length() > 0) {
            result.add(buf.toString().trim());
        }

        return result;
    }

    private String resolveNamespace(String category, Long userId) {
        if (userId != null) {
            return nsUserPrefix + userId;
        }

        if (category == null) {
            return nsTips;
        }

        return switch (category.toLowerCase()) {
            case "attraction", "attractions" -> nsAttractions;
            case "city", "cities" -> nsCities;
            case "hotel", "hotels" -> nsHotels;
            case "food", "meals" -> nsFood;
            default -> nsTips;
        };
    }

    private List<String> resolveQueryNamespaces(String category, Long userId) {
        Set<String> namespaces = new LinkedHashSet<>();

        if (category == null || category.isBlank() || "all".equalsIgnoreCase(category)) {
            namespaces.add(nsCities);
            namespaces.add(nsAttractions);
            namespaces.add(nsFood);
            namespaces.add(nsHotels);
        } else {
            switch (category.toLowerCase()) {
                case "food", "meals" -> {
                    namespaces.add(nsFood);
                    namespaces.add(nsCities);
                }
                case "hotel", "hotels" -> {
                    namespaces.add(nsHotels);
                    namespaces.add(nsCities);
                }
                case "attraction", "attractions" -> {
                    namespaces.add(nsAttractions);
                    namespaces.add(nsCities);
                }
                case "city", "cities" -> namespaces.add(nsCities);
                default -> {
                    namespaces.add(resolveNamespace(category, null));
                    namespaces.add(nsCities);
                }
            }
        }

        if (userId != null) {
            namespaces.add(nsUserPrefix + userId);
        }

        return new ArrayList<>(namespaces);
    }

    /**
     * 并行查询多个命名空间，带超时控制
     * @param queryVector 查询向量
     * @param namespaces 命名空间列表
     * @param topK 返回数量
     * @param result 结果收集器
     * @return 总结果数
     */
    private int queryNamespacesInParallel(List<Float> queryVector, List<String> namespaces, int topK, StringBuilder result) {
        log.info("[RAG检索]   启动并行查询: {} 个命名空间, topK={}", namespaces.size(), topK);
        long parallelStart = System.currentTimeMillis();

        // 限制命名空间数量，避免过多并行查询导致超时
        List<String> limitedNamespaces = namespaces.size() > 2 ? namespaces.subList(0, 2) : namespaces;
        if (namespaces.size() > 2) {
            log.warn("[RAG检索]   命名空间过多({})，限制为前2个: {}", namespaces.size(), limitedNamespaces);
        }

        // 为每个命名空间创建异步任务，带3秒超时
        List<CompletableFuture<NamespaceResult>> futures = limitedNamespaces.stream()
                .map(ns -> CompletableFuture.supplyAsync(() -> {
                    long nsStart = System.currentTimeMillis();
                    try {
                        log.debug("[RAG检索]     查询命名空间: '{}'", ns);
                        Object response = getIndex().queryByVector(topK, queryVector, ns, true, true);
                        StringBuilder nsResult = new StringBuilder();
                        int count = parseAndAppendResults(response, nsResult);
                        long nsTime = System.currentTimeMillis() - nsStart;
                        log.debug("[RAG检索]     命名空间 '{}' 查询完成: {} 条结果, 耗时={}ms", ns, count, nsTime);
                        return new NamespaceResult(ns, count, nsResult.toString());
                    } catch (Exception e) {
                        long nsTime = System.currentTimeMillis() - nsStart;
                        log.warn("[RAG检索]     命名空间 '{}' 查询失败 (耗时={}ms): 类型={} | 消息={}",
                                ns, nsTime, e.getClass().getSimpleName(), e.getMessage());
                        return new NamespaceResult(ns, 0, "");
                    }
                }))
                .toList();

        // 等待所有任务完成，缩短总超时，避免聊天链路被慢检索拖住
        CompletableFuture<Void> allDone = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
        );

        try {
            allDone.get(6, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("[RAG检索]   部分命名空间查询超时(>6s)，取消未完成任务");
            futures.forEach(f -> f.cancel(true));
        } catch (Exception e) {
            log.error("[RAG检索]   并行查询异常", e);
        }

        // 收集结果
        int totalResults = 0;
        StringBuilder nsSummary = new StringBuilder();
        for (CompletableFuture<NamespaceResult> future : futures) {
            try {
                // 跳过已取消的任务
                if (future.isCancelled()) {
                    continue;
                }
                NamespaceResult nsResult = future.getNow(new NamespaceResult("", 0, ""));
                if (nsResult.count > 0) {
                    result.append(nsResult.result);
                    totalResults += nsResult.count;
                    nsSummary.append(nsResult.namespace).append("(").append(nsResult.count).append(") ");
                }
            } catch (Exception e) {
                log.debug("[RAG检索]   获取命名空间结果失败: {}", e.getClass().getSimpleName());
            }
        }

        long parallelTime = System.currentTimeMillis() - parallelStart;
        if (totalResults > 0) {
            log.info("[RAG检索]   并行查询完成: 总结果={}, 来源={}, 总耗时={}ms",
                    totalResults, nsSummary.toString().trim(), parallelTime);
        } else {
            log.warn("[RAG检索]   并行查询完成: 无匹配结果, 耗时={}ms", parallelTime);
        }
        return totalResults;
    }

    /**
     * 命名空间查询结果内部类
     */
    private record NamespaceResult(String namespace, int count, String result) {}

    /**
     * 获取分类描述
     */
    private String getCategoryDescription(String category) {
        if (category == null) return "全部";
        return switch (category.toLowerCase()) {
            case "food", "meals" -> "美食";
            case "hotel", "hotels" -> "酒店";
            case "attraction", "attractions" -> "景点";
            case "city", "cities" -> "城市";
            case "all" -> "全部";
            default -> category;
        };
    }
}
