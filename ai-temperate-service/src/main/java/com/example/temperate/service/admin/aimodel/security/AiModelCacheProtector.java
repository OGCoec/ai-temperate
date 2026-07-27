package com.example.temperate.service.admin.aimodel.security;

import com.example.temperate.service.admin.aimodel.cache.AiModelCacheSnapshot;
import com.example.temperate.service.admin.aimodel.cache.ProtectedAiModelCacheSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 使用独立 AES-256-GCM 密钥保护启用 AI 模型的完整 Redis 快照。
 *
 * <p>每次加密使用随机 12 字节 IV，并以固定缓存 Key、用途和版本作为 AAD，防止密文被移动到其他
 * Key 或版本后仍能通过认证；该组件不记录明文或密文。</p>
 */
public final class AiModelCacheProtector {

    private static final String ENVELOPE_VERSION = "v1";
    private static final String AAD_PURPOSE = "ai-model-enabled-snapshot";
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom;
    private final SecretKeySpec encryptionKey;

    public AiModelCacheProtector(String encryptionKeyBase64, ObjectMapper objectMapper) {
        this(encryptionKeyBase64, objectMapper, new SecureRandom());
    }

    AiModelCacheProtector(
            String encryptionKeyBase64,
            ObjectMapper objectMapper,
            SecureRandom secureRandom) {
        byte[] keyBytes = decodeKey(encryptionKeyBase64);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.secureRandom = Objects.requireNonNull(secureRandom);
        this.encryptionKey = new SecretKeySpec(keyBytes, "AES");
        Arrays.fill(keyBytes, (byte) 0);
    }

    /**
     * 序列化并加密快照，同时返回明文大小供调用方执行缓存容量约束。
     */
    public ProtectedAiModelCacheSnapshot protect(
            String cacheKey,
            AiModelCacheSnapshot snapshot) {
        byte[] plaintext = null;
        try {
            plaintext = objectMapper.writeValueAsBytes(Objects.requireNonNull(snapshot));
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    encryptionKey,
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad(cacheKey));
            byte[] ciphertext = cipher.doFinal(plaintext);
            String envelope = ENVELOPE_VERSION
                    + "."
                    + encodeUrl(iv)
                    + "."
                    + encodeUrl(ciphertext);
            return new ProtectedAiModelCacheSnapshot(envelope, plaintext.length);
        } catch (GeneralSecurityException | JsonProcessingException exception) {
            throw new IllegalStateException("AI model cache encryption failed.", exception);
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    /**
     * 解密并验证缓存快照；版本、AAD、认证标签或 JSON 结构异常时统一拒绝。
     */
    public AiModelCacheSnapshot unprotect(String cacheKey, String envelope) {
        if (envelope == null || envelope.isBlank()) {
            throw new IllegalArgumentException("AI model cache envelope is missing.");
        }
        String[] parts = envelope.split("\\.", -1);
        if (parts.length != 3 || !ENVELOPE_VERSION.equals(parts[0])) {
            throw new IllegalArgumentException("AI model cache envelope is invalid.");
        }
        byte[] plaintext = null;
        try {
            byte[] iv = decodeUrl(parts[1]);
            byte[] ciphertext = decodeUrl(parts[2]);
            if (iv.length != IV_BYTES || ciphertext.length <= 16) {
                throw new IllegalArgumentException("AI model cache envelope is invalid.");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    encryptionKey,
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad(cacheKey));
            plaintext = cipher.doFinal(ciphertext);
            AiModelCacheSnapshot snapshot =
                    objectMapper.readValue(plaintext, AiModelCacheSnapshot.class);
            if (snapshot.schemaVersion() != AiModelCacheSnapshot.CURRENT_SCHEMA_VERSION) {
                throw new IllegalArgumentException("Unsupported AI model cache schema version.");
            }
            return snapshot;
        } catch (GeneralSecurityException | IOException exception) {
            throw new IllegalArgumentException("AI model cache envelope is invalid.", exception);
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    private static byte[] aad(String cacheKey) {
        if (cacheKey == null || cacheKey.isBlank()) {
            throw new IllegalArgumentException("AI model cache key is required for AAD.");
        }
        return (ENVELOPE_VERSION + "|" + AAD_PURPOSE + "|" + cacheKey)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] decodeKey(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length != 32
                    || !Base64.getEncoder().encodeToString(decoded).equals(value)) {
                throw new IllegalArgumentException("Non-canonical AES-256 key.");
            }
            return decoded;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalStateException(
                    "AI model cache encryption key must be canonical Base64 containing 32 bytes.",
                    exception);
        }
    }

    private static byte[] decodeUrl(String value) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (!encodeUrl(decoded).equals(value)) {
                throw new IllegalArgumentException("Non-canonical Base64URL.");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("AI model cache envelope is invalid.", exception);
        }
    }

    private static String encodeUrl(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
