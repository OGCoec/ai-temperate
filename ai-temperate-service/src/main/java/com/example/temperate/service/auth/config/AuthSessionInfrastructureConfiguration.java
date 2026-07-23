package com.example.temperate.service.auth.config;

import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配认证会话使用的 HMAC 标识保护组件。
 *
 * <p>该配置从受控环境配置读取密钥并构造会话密钥保护器；它不保存密钥、不签发会话，也不处理请求级认证。</p>
 */
@Configuration
public class AuthSessionInfrastructureConfiguration {

    @Bean
    AuthSessionSecretProtector authSessionSecretProtector(
            @Value("${app.auth-session.hmac-secret-base64}") String secretBase64) {
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(secretBase64);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Auth session HMAC secret must be canonical Base64.", exception);
        }
        // 回编码相等性只接受唯一的规范 Base64 表示，避免同一密钥以多种文本形式绕过配置审计。
        if (!Base64.getEncoder().encodeToString(secret).equals(secretBase64)) {
            throw new IllegalStateException("Auth session HMAC secret must be canonical Base64.");
        }
        return new AuthSessionSecretProtector(new HmacSha256Identifier(secret));
    }
}
