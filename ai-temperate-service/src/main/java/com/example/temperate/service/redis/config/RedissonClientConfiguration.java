package com.example.temperate.service.redis.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 该配置是来基于现有 Spring Data Redis 地址注册全应用唯一 RedissonClient，仅为需要看门狗的分布式锁提供协调能力。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RedisProperties.class)
public class RedissonClientConfiguration {

    private static final long LOCK_WATCHDOG_TIMEOUT_MILLIS = 30_000L;

    /**
     * Redisson Core 使用独立客户端但复用同一 Redis 单实例配置；销毁 Bean 时统一关闭连接与内部线程。
     */
    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient(RedisProperties properties) {
        Config config = new Config();
        // 延迟建立 Redis 连接，确保 Redis 临时不可用时应用仍能启动，并由请求路径降级到 PostgreSQL 唯一约束。
        config.setLazyInitialization(true);
        config.setLockWatchdogTimeout(LOCK_WATCHDOG_TIMEOUT_MILLIS);
        SingleServerConfig server = config.useSingleServer()
                .setAddress("redis://" + properties.getHost() + ":" + properties.getPort())
                .setDatabase(properties.getDatabase());
        if (StringUtils.hasText(properties.getUsername())) {
            server.setUsername(properties.getUsername());
        }
        if (StringUtils.hasText(properties.getPassword())) {
            server.setPassword(properties.getPassword());
        }
        return Redisson.create(config);
    }
}
