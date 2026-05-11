package com.travel.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.protobuf.Struct;
import com.travel.entity.KnowledgeDoc;
import com.travel.mapper.KnowledgeDocMapper;
import io.pinecone.clients.Index;
import io.pinecone.clients.Pinecone;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;
import java.util.*;

/**
 * RAG服务：向量化 + 检索
 *
 * 功能：
 * 1. 文档向量化并存储到Pinecone
 * 2. 相似性检索
 * 3. 文档删除
 *
 * 使用 DashScope REST API 生成向量
 * 向量维度：1024（text-embedding-v3）
 *
 * @author Travel System
 * @version 2.0
 */
@Service
@RequiredArgsConstructor
public class RagService extends ServiceImpl<KnowledgeDocMapper, KnowledgeDoc> {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    // ==================== 依赖注入 ====================

    private final Pinecone pineconeClient;

    // RestTemplate 直接实例化（不使用构造注入，避免 Spring 容器中找不到 bean）
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

    // ==================== 常量 ====================

    private static final int EMBEDDING_MAX_LENGTH = 8000;
    private static final int DEFAULT_CHUNK_SIZE = 1000;
    private static final int DEFAULT_OVERLAP = 200;
    private static final double SIMILARITY_THRESHOLD = 0.5;

    private static final String DASHSCOPE_EMBEDDING_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";

    // ==================== 公开方法 ====================

    /**
     * 将文档向量化并存储到Pinecone
     */
    public void indexDocument(KnowledgeDoc doc) {
        log.info("[RAG] 开始向量化文档: docId={}, title={}", doc.getId(), doc.getTitle());

        List<String> chunks = splitIntoChunks(doc.getContent(), DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP);
        log.info("[RAG] 文档分块完成: docId={}, chunks={}", doc.getId(), chunks.size());

        String namespace = resolveNamespace(doc.getCategory(), doc.getUserId());
        log.debug("[RAG] 使用命名空间: {}", namespace);

        List<String> pineconeIds = new ArrayList<>();
        Index index = getIndex();

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            String chunkId = doc.getId() + "_chunk_" + i;

            float[] embedding = dashscopeEmbed(chunk);
            List<Float> vector = floatsToList(embedding);

            // 构建元数据（Pinecone 需要 Struct 类型）
            Struct.Builder structBuilder = Struct.newBuilder();
            structBuilder.putFields("docId", com.google.protobuf.Value.newBuilder().setStringValue(String.valueOf(doc.getId())).build());
            structBuilder.putFields("title", com.google.protobuf.Value.newBuilder().setStringValue(doc.getTitle() != null ? doc.getTitle() : "").build());
            structBuilder.putFields("category", com.google.protobuf.Value.newBuilder().setStringValue(doc.getCategory() != null ? doc.getCategory() : "").build());
            structBuilder.putFields("chunkIndex", com.google.protobuf.Value.newBuilder().setNumberValue(i).build());
            structBuilder.putFields("text", com.google.protobuf.Value.newBuilder().setStringValue(chunk).build());
            Struct metadata = structBuilder.build();

            index.upsert(chunkId, vector, null, null, metadata, namespace);
            pineconeIds.add(chunkId);

            log.debug("[RAG] 块向量化完成: chunkId={}, length={}", chunkId, chunk.length());
        }

        doc.setPineconeIds(pineconeIds);
        doc.setStatus(1);
        updateById(doc);

        log.info("[RAG] 文档向量化完成: docId={}, totalChunks={}, namespace={}",
                doc.getId(), chunks.size(), namespace);
    }

    /**
     * 相似性检索
     */
    public String query(String query, String category, Long userId, int topK) {
        log.info("[RAG] 开始检索: query={}, category={}, userId={}, topK={}",
                query, category, userId, topK);

        try {
            float[] queryEmbedding = dashscopeEmbed(query);
            List<Float> queryVector = floatsToList(queryEmbedding);

            List<String> namespaces = resolveQueryNamespaces(category, userId);

            StringBuilder result = new StringBuilder();
            int totalResults = 0;

            for (String ns : namespaces) {
                log.debug("[RAG] 在命名空间 {} 中检索", ns);

                try {
                    // Pinecone SDK queryByVector 参数：topK, vector, namespace, includeValues, includeMetadata
                    // 注意：此方法为阻塞调用，确保参数类型正确
                    Object response = getIndex().queryByVector(topK, queryVector, ns, true, true);

                    int count = parseAndAppendResults(response, result);
                    totalResults += count;

                } catch (Exception e) {
                    log.warn("[RAG] 命名空间 {} 查询失败: {}", ns, e.getMessage());
                }
            }

            if (totalResults == 0) {
                log.info("[RAG] 检索无结果: query={}", query);
                return "知识库中暂无相关信息";
            }

            log.info("[RAG] 检索完成: query={}, totalResults={}", query, totalResults);
            return result.toString();

        } catch (Exception e) {
            log.error("[RAG] 检索失败: query={}", query, e);
            return "知识库查询失败：" + e.getMessage();
        }
    }

    /**
     * 删除文档向量
     */
    public void deleteDocument(KnowledgeDoc doc) {
        if (doc.getPineconeIds() == null || doc.getPineconeIds().isEmpty()) {
            log.warn("[RAG] 文档无向量ID: docId={}", doc.getId());
            return;
        }

        String namespace = resolveNamespace(doc.getCategory(), doc.getUserId());

        try {
            getIndex().deleteByIds(doc.getPineconeIds(), namespace);
            log.info("[RAG] 文档向量删除成功: docId={}, namespace={}, vectors={}",
                    doc.getId(), namespace, doc.getPineconeIds().size());
        } catch (Exception e) {
            log.error("[RAG] 文档向量删除失败: docId={}", doc.getId(), e);
            throw new RuntimeException("删除向量失败", e);
        }
    }

    /**
     * 批量向量化文档
     */
    public void batchIndexDocuments(List<KnowledgeDoc> docs) {
        log.info("[RAG] 开始批量向量化: count={}", docs.size());

        for (KnowledgeDoc doc : docs) {
            try {
                indexDocument(doc);
            } catch (Exception e) {
                log.error("[RAG] 文档向量化失败: docId={}", doc.getId(), e);
            }
        }

        log.info("[RAG] 批量向量化完成");
    }

    // ==================== 私有方法 ====================

    private Index getIndex() {
        Index index = pineconeClient.getIndexConnection(indexName);
        if (index == null) {
            throw new RuntimeException("Pinecone索引连接失败，请确认索引 [" + indexName + "] 已创建且状态为Ready");
        }
        return index;
    }

    private float[] dashscopeEmbed(String text) {
        if (text.length() > EMBEDDING_MAX_LENGTH) {
            log.warn("[RAG] 文本超长({})，截断到{}", text.length(), EMBEDDING_MAX_LENGTH);
            text = text.substring(0, EMBEDDING_MAX_LENGTH);
        }

        try {
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

            log.debug("[RAG] 调用DashScope API: model={}, textLength={}", embeddingModel, text.length());

            ResponseEntity<String> responseEntity = restTemplate.exchange(
                    DASHSCOPE_EMBEDDING_URL,
                    HttpMethod.POST,
                    requestEntity,
                    String.class
            );

            String response = responseEntity.getBody();
            return parseEmbeddingResponse(response);

        } catch (Exception e) {
            log.error("[RAG] DashScope embedding调用失败: {}", e.getMessage(), e);
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

            log.debug("[RAG] embedding生成成功: dimension={}", result.length);
            return result;

        } catch (Exception e) {
            log.error("[RAG] 解析embedding响应失败: {}", json, e);
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

    /**
     * 解析查询结果（兼容 Pinecone SDK 的 QueryResponseWithUnsignedIndices）
     * 使用反射自动查找正确的方法名，避免硬编码导致 NoSuchMethodException
     */
    private int parseAndAppendResults(Object response, StringBuilder result) {
        try {
            // 反射查找返回 List 的方法（getResults / getMatches / getResultsList 等）
            Method resultsMethod = findListMethod(response.getClass());
            if (resultsMethod == null) {
                log.error("[RAG] 无法找到返回 List 的方法，response class={}", response.getClass().getName());
                return 0;
            }

            Object matchesObj = resultsMethod.invoke(response);
            if (!(matchesObj instanceof List<?> matches) || matches.isEmpty()) {
                return 0;
            }

            int count = 0;
            for (Object item : matches) {
                // 获取相似度分数
                Method getScoreMethod = findMethod(item.getClass(), "getScore");
                if (getScoreMethod == null) continue;
                double score = ((Number) getScoreMethod.invoke(item)).doubleValue();

                if (score < SIMILARITY_THRESHOLD) {
                    continue;
                }

                // 获取元数据
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
                                log.warn("[RAG] 解析元数据字段失败: key={}", key);
                            }
                        }
                    }
                }

                if (!text.isEmpty()) {
                    result.append("【").append(title).append("】 (相似度: ").append(String.format("%.2f", score)).append(")\n");
                    
                    // 地址精度检测：如果不包含门牌号（数字+号），标注提醒AI
                    if (!hasExactAddress(text)) {
                        result.append("⚠️【地址未精确到门牌号】此结果中的地址缺少门牌号，请在输出前调用 web_search 搜索精确地址！\n");
                    }
                    
                    result.append(text).append("\n\n");
                    count++;
                }
            }

            return count;

        } catch (Exception e) {
            log.error("[RAG] 解析查询结果失败", e);
            return 0;
        }
    }

    /**
     * 在类中查找返回 List 的无参方法（用于解析 Pinecone 响应）
     */
    private Method findListMethod(Class<?> clazz) {
        for (Method m : clazz.getMethods()) {
            if (m.getParameterCount() == 0 && List.class.isAssignableFrom(m.getReturnType())) {
                log.debug("[RAG] 找到 List 方法: {}.{}()", clazz.getSimpleName(), m.getName());
                return m;
            }
        }
        return null;
    }

    /**
     * 在类中查找指定名称的无参方法（不抛异常）
     */
    private Method findMethod(Class<?> clazz, String name) {
        try {
            return clazz.getMethod(name);
        } catch (NoSuchMethodException e) {
            // 尝试 protobuf 风格（首字母大写）
            try {
                return clazz.getMethod(name.substring(0, 1).toUpperCase() + name.substring(1));
            } catch (NoSuchMethodException e2) {
                log.warn("[RAG] 方法未找到: {}.{}()", clazz.getSimpleName(), name);
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
                log.warn("[RAG] 块超长({})，强制分割", chunk.length());
                safeChunks.addAll(splitOversizedChunk(chunk, EMBEDDING_MAX_LENGTH));
            }
        }

        if (safeChunks.isEmpty()) {
            String truncated = text.substring(0, Math.min(text.length(), EMBEDDING_MAX_LENGTH));
            safeChunks.add(truncated);
        }

        return safeChunks;
    }

    /**
     * 检测文本中是否包含精确到门牌号的地址
     * 判断标准：包含"数字+号/栋/弄/座/铺"或具体地标名称
     */
    private boolean hasExactAddress(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        // 模式1：包含"数字+号/栋/弄/座/铺"（如"36号"、"4688号"、"6栋"、"1弄"）
        if (text.matches(".*\\d+[号栋弄座铺].*")) {
            return true;
        }



        // 模式3：包含具体地标名称（知名景点/建筑/机构）
        // 这些地标通常有精确坐标，可以认为地址是精确的
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

        // 模式4：包含区+具体路名+数字（如"吉阳区海棠北路36号"）
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

    /**
     * 根据 category 解析需要查询的所有 namespace 列表
     * - all：查询所有公共 namespace
     * - 指定分类：查询对应 namespace + 通用 tips
     * - 用户私有：额外加入 user-{userId}
     */
    private List<String> resolveQueryNamespaces(String category, Long userId) {
        List<String> namespaces = new ArrayList<>();

        if ("all".equalsIgnoreCase(category)) {
            // 查询所有公共 namespace
            namespaces.add(nsAttractions);
            namespaces.add(nsCities);
            namespaces.add(nsHotels);
            namespaces.add(nsFood);
            namespaces.add(nsTips);
        } else if (category != null) {
            // 查询指定分类对应的 namespace
            String targetNs = resolveNamespace(category, null);
            namespaces.add(targetNs);
            // 【关键修复】知识库文件按城市组织，所有数据都在 cities namespace 中
            // 必须同时查 cities，否则 hotel/food/attraction 查询会漏掉数据
            if (!nsCities.equals(targetNs)) {
                namespaces.add(nsCities);
            }
            // 同时查询通用 tips，补充旅行常识
            if (!nsTips.equals(targetNs)) {
                namespaces.add(nsTips);
            }
        } else {
            // category 为 null，默认查所有
            namespaces.add(nsAttractions);
            namespaces.add(nsCities);
            namespaces.add(nsHotels);
            namespaces.add(nsFood);
            namespaces.add(nsTips);
        }

        // 如有 userId，额外查询用户私有 namespace
        if (userId != null) {
            namespaces.add(nsUserPrefix + userId);
        }

        return namespaces;
    }
}
