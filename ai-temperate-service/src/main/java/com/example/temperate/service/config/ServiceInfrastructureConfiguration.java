package com.example.temperate.service.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 配置 service 层共享的基础设施 Bean。
 *
 * <p>当前仅提供统一 UTC 时钟，业务服务应注入该时钟以获得可测试且一致的时间来源。</p>
 */
@Configuration
public class ServiceInfrastructureConfiguration {

    @Bean
    Clock applicationClock() {
        return Clock.systemUTC();
    }
}
