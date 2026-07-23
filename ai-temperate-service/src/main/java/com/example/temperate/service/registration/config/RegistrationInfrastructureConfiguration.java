package com.example.temperate.service.registration.config;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.registration.flow.security.RegistrationTokenProtector;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 装配注册流程所需的 Redis Key、HMAC、令牌保护和异步投递基础设施。
 *
 * <p>该配置只负责受控依赖创建和配置格式校验；注册状态机、验证码规则和业务事务由相应服务负责。</p>
 */
@Configuration
public class RegistrationInfrastructureConfiguration {

    @Bean
    RedisKeyFactory redisKeyFactory(
            @Value("${app.registration.environment}") String environment) {
        return new RedisKeyFactory(environment.toLowerCase(Locale.ROOT));
    }

    @Bean
    HmacSha256Identifier registrationHmacIdentifier(
            @Value("${app.registration.hmac-secret-base64}") String secretBase64) {
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(secretBase64);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Registration HMAC secret must be canonical Base64.", exception);
        }
        // 仅接受唯一规范 Base64 文本，避免同一密钥使用不同配置文本绕过审计或产生环境差异。
        if (!Base64.getEncoder().encodeToString(secret).equals(secretBase64)) {
            throw new IllegalStateException("Registration HMAC secret must be canonical Base64.");
        }
        return new HmacSha256Identifier(secret);
    }

    @Bean
    RegistrationTokenProtector registrationTokenProtector(
            HmacSha256Identifier registrationHmacIdentifier,
            AuthSessionSecretProtector authSessionSecretProtector) {
        return new RegistrationTokenProtector(
                registrationHmacIdentifier, authSessionSecretProtector);
    }

    @Bean
    PublicIdCodec publicIdCodec() {
        return new PublicIdCodec();
    }

    @Bean(name = "registrationDeliveryExecutor")
    Executor registrationDeliveryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("registration-delivery-");
        executor.initialize();
        return executor;
    }
}
