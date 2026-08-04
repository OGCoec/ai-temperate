package com.example.temperate.service.auth.totp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 启用 TOTP 强类型配置并让算法、密钥保护和流程服务共享同一组安全边界。
 */
@Configuration
@EnableConfigurationProperties(TotpProperties.class)
public class TotpConfiguration {
}
