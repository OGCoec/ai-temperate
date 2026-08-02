package com.example.temperate.service.user.profile.config;

import com.example.temperate.service.user.profile.cache.security.UserProfileCacheIdProtector;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配普通用户资料 Redis Key 的 AES-256-KWP 保护器及其受校验配置。
 *
 * <p>该配置不创建新的 Redis 连接或线程池，统一复用应用已有的 {@code StringRedisTemplate} 和
 * {@code ObjectMapper} 基础设施。</p>
 */
@Configuration
@EnableConfigurationProperties(UserProfileCacheProperties.class)
public class UserProfileCacheConfiguration {

    @Bean
    UserProfileCacheIdProtector userProfileCacheIdProtector(
            UserProfileCacheProperties properties) {
        return new UserProfileCacheIdProtector(properties.idEncryptionKeyBase64());
    }
}
