package com.example.temperate.service.registration.config;

import com.example.temperate.service.registration.verification.delivery.rabbit.VerificationDeliveryPayloadProtector;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配验证码投递消息 payload 的加密保护组件。
 *
 * <p>RabbitMQ 消息会持久化到 broker，因此验证码和投递目标必须先加密再进入消息体；密钥只允许来自环境变量或 Secret。</p>
 */
@Configuration
public class VerificationDeliverySecurityConfiguration {

    @Bean
    VerificationDeliveryPayloadProtector verificationDeliveryPayloadProtector(
            @Value("${app.registration.delivery.payload-key-base64}") String keyBase64,
            ObjectMapper objectMapper) {
        try {
            byte[] key = Base64.getDecoder().decode(keyBase64);
            if (!Base64.getEncoder().encodeToString(key).equals(keyBase64)) {
                throw new IllegalStateException(
                        "Verification delivery payload key must be canonical Base64.");
            }
            return new VerificationDeliveryPayloadProtector(key, objectMapper);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Verification delivery payload key must be Base64 encoded 32 bytes.",
                    exception);
        }
    }
}
