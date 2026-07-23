package com.example.temperate.service.registration.verification.delivery.rabbit;

import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationPurpose;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 对 RabbitMQ 中必须跨进程传递的验证码目标和验证码明文做应用级加密。
 *
 * <p>Redis 仍只保存验证码摘要；消息队列为了异步发送必须临时携带目标和验证码，因此使用 AES-GCM 防止队列持久化内容泄露。</p>
 */
public final class VerificationDeliveryPayloadProtector {

    private static final String VERSION = "v1";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom;

    public VerificationDeliveryPayloadProtector(byte[] key, ObjectMapper objectMapper) {
        this(key, objectMapper, new SecureRandom());
    }

    VerificationDeliveryPayloadProtector(
            byte[] key, ObjectMapper objectMapper, SecureRandom secureRandom) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("Verification delivery payload key must be 32 bytes.");
        }
        this.key = new SecretKeySpec(key.clone(), "AES");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom must not be null");
    }

    public String protect(VerificationDeliveryRequest request) {
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] json = objectMapper.writeValueAsBytes(new Payload(
                    request.destination(), request.code(), request.purpose()));
            byte[] encrypted = cipher.doFinal(json);
            return VERSION + "." + ENCODER.encodeToString(iv) + "."
                    + ENCODER.encodeToString(encrypted);
        } catch (GeneralSecurityException | JsonProcessingException exception) {
            throw new IllegalStateException("Verification delivery payload encryption failed.", exception);
        }
    }

    public VerificationDeliveryRequest unprotect(String protectedPayload) {
        try {
            String[] parts = protectedPayload == null ? new String[0] : protectedPayload.split("\\.");
            if (parts.length != 3 || !VERSION.equals(parts[0])) {
                throw new IllegalArgumentException("Protected payload version is invalid.");
            }
            byte[] iv = DECODER.decode(parts[1]);
            byte[] encrypted = DECODER.decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] json = cipher.doFinal(encrypted);
            Payload payload = objectMapper.readValue(
                    new String(json, StandardCharsets.UTF_8), Payload.class);
            return new VerificationDeliveryRequest(
                    payload.destination(), payload.code(), payload.purpose());
        } catch (GeneralSecurityException | JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException("Verification delivery payload decryption failed.", exception);
        }
    }

    public record Payload(String destination, String code, VerificationPurpose purpose) {
    }
}
