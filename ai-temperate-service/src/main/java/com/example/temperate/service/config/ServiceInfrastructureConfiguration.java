package com.example.temperate.service.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 配置 service 层共享的基础设施 Bean。
 *
 * <p>提供统一 UTC 时钟和唯一 Redis Pub/Sub 监听容器；业务通过独立频道隔离，避免重复容器 Bean 造成注入歧义。</p>
 */
@Configuration
public class ServiceInfrastructureConfiguration {

    @Bean
    Clock applicationClock() {
        return Clock.systemUTC();
    }

    /**
     * 邮件检查和 AI Generation 共享唯一 Redis Pub/Sub 监听容器，各业务继续使用不同频道隔离事件。
     *
     * @param connectionFactory 项目唯一 Redis 连接工厂
     * @return service 层共享监听容器
     */
    @Bean
    RedisMessageListenerContainer serviceRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }
}
