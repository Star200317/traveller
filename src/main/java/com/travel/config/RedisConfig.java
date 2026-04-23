package com.travel.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Redis配置 - 当前禁用
 * 如需启用Redis，删除application.yml中的spring.autoconfigure.exclude配置
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "spring.data.redis.enabled", havingValue = "true")
public class RedisConfig {
    // Redis已禁用，此配置类不会加载
}
