package com.travel.config;

import io.pinecone.clients.Index;
import io.pinecone.clients.Pinecone;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PineconeConfig {

    @Value("${pinecone.api-key}")
    private String apiKey;

    @Value("${pinecone.index-name:travel-guide}")
    private String indexName;

    /**
     * Pinecone客户端 - 单例复用
     */
    @Bean
    public Pinecone pineconeClient() {
        return new Pinecone.Builder(apiKey).build();
    }

    /**
     * Pinecone索引连接 - 预获取并缓存，避免每次查询重新创建连接
     */
    @Bean
    public Index pineconeIndex(Pinecone pinecone) {
        Index index = pinecone.getIndexConnection(indexName);
        if (index == null) {
            throw new RuntimeException("Pinecone索引连接失败，请确认索引 [" + indexName + "] 已创建且状态为Ready");
        }
        return index;
    }
}
