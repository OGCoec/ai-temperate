package com.example.temperate.common.id.snowflake.config;

import com.example.temperate.common.codec.id.HybridUlidCodec;
import com.example.temperate.common.id.snowflake.component.HybridSemaphoreIdWorker;
import com.example.temperate.common.id.snowflake.component.SnowflakeIdWorker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 注册 Snowflake 与混合 ID 生成器的 Spring Bean。
 *
 * <p>本类只负责依赖装配和工作节点参数传递；ID 位布局、并发序列控制和时钟回拨处理由具体生成器负责。</p>
 */
@Configuration
public class SnowflakeConfiguration {

    @Bean
    public SnowflakeIdWorker snowflakeIdWorker(StringRedisTemplate stringRedisTemplate) {
        return new SnowflakeIdWorker(1L, 1L, stringRedisTemplate);
    }

    @Bean
    public HybridSemaphoreIdWorker hybridSemaphoreIdWorker() {
        return new HybridSemaphoreIdWorker(1L, 1L);
    }

    @Bean
    public HybridUlidCodec hybridUlidCodec() {
        return new HybridUlidCodec();
    }
}
