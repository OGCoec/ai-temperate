package com.example.temperate.service.admin.aimodel.config;

import com.example.temperate.service.admin.aimodel.icon.remote.config.AiModelIconRemoteSvgProperties;
import com.example.temperate.service.admin.aimodel.security.AiModelCacheProtector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配 AI 模型缓存、官方 SVG 主机边界配置和独立 AES-GCM 保护器。
 *
 * <p>该配置不创建 Redis 客户端或 Snowflake Worker，统一复用项目已经存在的单例基础设施。</p>
 */
@Configuration
@EnableConfigurationProperties({
        AiModelCacheProperties.class,
        AiModelIconRemoteSvgProperties.class
})
public class AiModelInfrastructureConfiguration {

    @Bean
    AiModelCacheProtector aiModelCacheProtector(
            AiModelCacheProperties properties,
            ObjectMapper objectMapper) {
        return new AiModelCacheProtector(properties.encryptionKeyBase64(), objectMapper);
    }
}
