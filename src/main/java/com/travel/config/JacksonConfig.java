package com.travel.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Jackson 全局配置
 * 
 * 解决 JavaScript 大数字精度丢失问题：
 * Java 的 Long（雪花ID，19位）超过 JS Number.MAX_SAFE_INTEGER（9007199254740991，16位）
 * 前端收到后精度丢失，导致 ID 变成错误值（如 2053135275390230530 → 2053135275390230500）
 * 
 * 解决方案：所有 Long 类型序列化为字符串
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        ObjectMapper objectMapper = builder.createXmlMapper(false).build();
        
        // 将所有 Long 类型序列化为 String，避免前端精度丢失
        SimpleModule module = new SimpleModule();
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);
        objectMapper.registerModule(module);
        
        return objectMapper;
    }
}
