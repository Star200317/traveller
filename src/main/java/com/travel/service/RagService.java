package com.travel.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.protobuf.Struct;
import com.travel.entity.KnowledgeDoc;
import com.travel.mapper.KnowledgeDocMapper;
import io.pinecone.clients.Index;
import io.pinecone.clients.Pinecone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.*;

/**
 * RAG服务：向量化 + 检索
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService extends ServiceImpl<KnowledgeDocMapper, KnowledgeDoc> {

    private final Pinecone pineconeClient;
    private final EmbeddingModel embeddingModel;  // Spring AI自动注入DashScope embedding
    
    @Value("${pinecone.index-name}")
    private String indexName;
    
    // 延迟获取Index
    private Index getIndex() {
        return pineconeClient.getIndexConnection(indexName);
    }

    @Value("${pinecone.namespace.attractions}")
    private String nsAttractions;
    @Value("${pinecone.namespace.cities}")
    private String nsCities;
    @Value("${pinecone.namespace.tips}")
    private String nsTips;
    @Value("${pinecone.namespace.user-prefix}")
    private String nsUserPrefix;

    /**
     * 将文档向量化并存入Pinecone
     */
    public void indexDocument(KnowledgeDoc doc) {
        // 1. 分块（简单按段落分块，每块≤500字）
        List<String> chunks = splitIntoChunks(doc.getContent(), 500);
        List<String> pineconeIds = new ArrayList<>();

        String namespace = resolveNamespace(doc.getCategory(), doc.getUserId());

        for (int i = 0; i < chunks.size(); i++) {
            String chunkId = doc.getId() + "_chunk_" + i;
            String text = chunks.get(i);

            // 2. 调用DashScope embedding生成向量
            float[] embedding = embeddingModel.embed(text);
            List<Float> vector = new ArrayList<>();
            for (float f : embedding) {
                vector.add(f);
            }

            // 3. 构建metadata（转成 protobuf Struct）
            Map<String, String> metaMap = new HashMap<>();
            metaMap.put("docId", String.valueOf(doc.getId()));
            metaMap.put("title", doc.getTitle());
            metaMap.put("category", doc.getCategory());
            metaMap.put("text", text);
            
            Struct.Builder structBuilder = Struct.newBuilder();
            for (Map.Entry<String, String> entry : metaMap.entrySet()) {
                structBuilder.putFields(entry.getKey(), 
                    com.google.protobuf.Value.newBuilder().setStringValue(entry.getValue()).build());
            }
            Struct metadata = structBuilder.build();

            // 4. Upsert到Pinecone
            getIndex().upsert(chunkId, vector, null, null, metadata, namespace);
            pineconeIds.add(chunkId);
        }

        // 5. 更新DB中的pineconeIds和状态
        doc.setPineconeIds(pineconeIds);
        doc.setStatus(1);
        updateById(doc);
        log.info("[RAG] 文档已向量化: docId={}, chunks={}", doc.getId(), chunks.size());
    }

    /**
     * 相似性检索
     *
     * @param query    查询文本
     * @param category 分类（attraction/city/tip/all）
     * @param userId   用户ID（null=只查公共库）
     * @param topK     返回条数
     */
    public String query(String query, String category, Long userId, int topK) {
        try {
            float[] emb = embeddingModel.embed(query);
            List<Float> queryVector = new ArrayList<>();
            for (float f : emb) {
                queryVector.add(f);
            }

            List<String> namespaces = resolveQueryNamespaces(category, userId);
            StringBuilder result = new StringBuilder();

            for (String ns : namespaces) {
                try {
                    // 调用 Pinecone 查询
                    Object resp = getIndex().queryByVector(topK, queryVector, ns, true, true);
                    
                    // 通过反射提取结果
                    Method getResultsMethod = resp.getClass().getMethod("getResults");
                    Object results = getResultsMethod.invoke(resp);
                    
                    if (results instanceof List<?> matches && !matches.isEmpty()) {
                        for (Object item : matches) {
                            // 获取 score
                            Method getScoreMethod = item.getClass().getMethod("getScore");
                            double score = ((Number) getScoreMethod.invoke(item)).doubleValue();
                            
                            if (score > 0.7) {
                                // 获取 metadata
                                Method getMetadataMethod = item.getClass().getMethod("getMetadata");
                                Object metadata = getMetadataMethod.invoke(item);
                                
                                Map<String, String> meta = new HashMap<>();
                                if (metadata != null) {
                                    Method getFieldsMethod = metadata.getClass().getMethod("getFieldsMap");
                                    @SuppressWarnings("unchecked")
                                    Map<String, ?> fields = (Map<String, ?>) getFieldsMethod.invoke(metadata);
                                    fields.forEach((k, v) -> {
                                        try {
                                            meta.put(k, v.getClass().getMethod("getStringValue").invoke(v).toString());
                                        } catch (Exception ex) {
                                            meta.put(k, v.toString());
                                        }
                                    });
                                }
                                result.append("【").append(meta.getOrDefault("title", "")).append("】\n");
                                result.append(meta.getOrDefault("text", "")).append("\n\n");
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("[RAG] 命名空间 {} 查询失败: {}", ns, e.getMessage());
                }
            }
            return result.length() > 0 ? result.toString() : "知识库中暂无相关信息";
        } catch (Exception e) {
            log.error("[RAG] 查询失败: {}", query, e);
            return "知识库查询失败";
        }
    }

    /**
     * 删除文档向量
     */
    public void deleteDocument(KnowledgeDoc doc) {
        if (doc.getPineconeIds() != null && !doc.getPineconeIds().isEmpty()) {
            String namespace = resolveNamespace(doc.getCategory(), doc.getUserId());
            getIndex().deleteByIds(doc.getPineconeIds(), namespace);
        }
    }

    // ---- 私有方法 ----

    private List<String> splitIntoChunks(String text, int maxLen) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\n{2,}");
        StringBuilder current = new StringBuilder();
        for (String para : paragraphs) {
            if (current.length() + para.length() > maxLen && current.length() > 0) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
            }
            current.append(para).append("\n\n");
        }
        if (current.length() > 0) chunks.add(current.toString().trim());
        return chunks.isEmpty() ? List.of(text) : chunks;
    }

    private String resolveNamespace(String category, Long userId) {
        if (userId != null) return nsUserPrefix + userId;
        return switch (category) {
            case "attraction" -> nsAttractions;
            case "city" -> nsCities;
            default -> nsTips;
        };
    }

    private List<String> resolveQueryNamespaces(String category, Long userId) {
        if ("all".equals(category)) {
            List<String> ns = new ArrayList<>(List.of(nsAttractions, nsCities, nsTips));
            if (userId != null) ns.add(nsUserPrefix + userId);
            return ns;
        }
        return List.of(resolveNamespace(category, userId));
    }
}
