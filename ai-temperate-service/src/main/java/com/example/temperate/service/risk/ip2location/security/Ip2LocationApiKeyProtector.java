package com.example.temperate.service.risk.ip2location.security;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.risk.ip2location.domain.Ip2LocationKeyMaterial;
import com.example.temperate.service.risk.ip2location.domain.Ip2LocationPlanType;
import com.example.temperate.service.risk.ip2location.domain.ProtectedIp2LocationKey;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 使用用途隔离的 HMAC 与 AES-256-GCM 保护 IP2Location API Key。
 *
 * <p>确定性 HMAC 只用于去重和 Redis Hash Field；可逆密文使用随机 IV，并通过 AAD 绑定业务版本与
 * Key ID，防止密文跨字段替换。该组件不记录明文、密文或完整标识。</p>
 */
public final class Ip2LocationApiKeyProtector {

    private static final String ENVELOPE_VERSION = "v1";
    private static final String IDENTIFIER_PURPOSE = "ait-ip2location-key-id-v1";
    private static final String ENCRYPTION_PURPOSE = "ait-ip2location-key-encryption-v1";
    private static final String IDENTIFIER_INPUT_PREFIX = "ip2location-api-key\u0000";
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom;
    private final HmacSha256Identifier identifier;
    private final SecretKeySpec encryptionKey;

    public Ip2LocationApiKeyProtector(
            String masterSecretBase64,
            ObjectMapper objectMapper) {
        this(masterSecretBase64, objectMapper, new SecureRandom());
    }

    Ip2LocationApiKeyProtector(
            String masterSecretBase64,
            ObjectMapper objectMapper,
            SecureRandom secureRandom) {
        byte[] master = decodeMasterSecret(masterSecretBase64);
        byte[] identifierKey = derive(master, IDENTIFIER_PURPOSE);
        byte[] encryptionBytes = derive(master, ENCRYPTION_PURPOSE);
        Arrays.fill(master, (byte) 0);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.secureRandom = Objects.requireNonNull(secureRandom);
        this.identifier = new HmacSha256Identifier(identifierKey);
        this.encryptionKey = new SecretKeySpec(encryptionBytes, "AES");
        Arrays.fill(identifierKey, (byte) 0);
        Arrays.fill(encryptionBytes, (byte) 0);
    }

    /**
     * 规范化、标识并加密一个 API Key，返回值可以安全写入 Redis。
     */
    public ProtectedIp2LocationKey protect(
            String rawApiKey,
            Ip2LocationPlanType planType,
            Instant createdAt,
            Instant expiresAt) {
        String normalized = normalizeApiKey(rawApiKey);
        HmacIdentifier keyId = identifier.identify(IDENTIFIER_INPUT_PREFIX + normalized);
        Ip2LocationKeyMaterial material = new Ip2LocationKeyMaterial(
                Ip2LocationKeyMaterial.CURRENT_SCHEMA_VERSION,
                normalized,
                mask(normalized),
                Objects.requireNonNull(planType),
                Objects.requireNonNull(createdAt),
                Objects.requireNonNull(expiresAt));
        return new ProtectedIp2LocationKey(
                keyId,
                encrypt(material, keyId),
                expiresAt);
    }

    /**
     * 解密并验证 Redis 中的凭据；认证标签、版本或绑定标识错误时统一失败。
     */
    public Ip2LocationKeyMaterial unprotect(
            HmacIdentifier keyId,
            String envelope) {
        Objects.requireNonNull(keyId);
        if (envelope == null || envelope.isBlank()) {
            throw new IllegalArgumentException("Encrypted API key envelope is missing.");
        }
        String[] parts = envelope.split("\\.", -1);
        if (parts.length != 3 || !ENVELOPE_VERSION.equals(parts[0])) {
            throw new IllegalArgumentException("Encrypted API key envelope is invalid.");
        }
        byte[] plaintext = null;
        try {
            byte[] iv = decodeUrl(parts[1]);
            byte[] ciphertext = decodeUrl(parts[2]);
            if (iv.length != IV_BYTES || ciphertext.length <= 16) {
                throw new IllegalArgumentException("Encrypted API key envelope is invalid.");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    encryptionKey,
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad(keyId));
            plaintext = cipher.doFinal(ciphertext);
            Ip2LocationKeyMaterial material =
                    objectMapper.readValue(plaintext, Ip2LocationKeyMaterial.class);
            if (material.schemaVersion()
                    != Ip2LocationKeyMaterial.CURRENT_SCHEMA_VERSION) {
                throw new IllegalArgumentException("Unsupported API key schema.");
            }
            return material;
        } catch (GeneralSecurityException | IOException exception) {
            throw new IllegalArgumentException("Encrypted API key envelope is invalid.", exception);
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    private String encrypt(
            Ip2LocationKeyMaterial material,
            HmacIdentifier keyId) {
        byte[] plaintext = null;
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            plaintext = objectMapper.writeValueAsBytes(material);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    encryptionKey,
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad(keyId));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return ENVELOPE_VERSION
                    + "."
                    + encodeUrl(iv)
                    + "."
                    + encodeUrl(ciphertext);
        } catch (GeneralSecurityException | JsonProcessingException exception) {
            throw new IllegalStateException("IP2Location API key encryption failed.", exception);
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    private static byte[] aad(HmacIdentifier keyId) {
        return (ENVELOPE_VERSION + "|ip2location-api-key|" + keyId.value())
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] derive(byte[] master, String purpose) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(master, "HmacSHA256"));
            return mac.doFinal(purpose.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable.", exception);
        }
    }

    private static byte[] decodeMasterSecret(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length < 32
                    || !Base64.getEncoder().encodeToString(decoded).equals(value)) {
                throw new IllegalArgumentException(
                        "IP2Location encryption key must be canonical Base64.");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "IP2Location encryption key must contain at least 32 bytes.",
                    exception);
        }
    }

    private static String normalizeApiKey(String value) {
        if (value == null) {
            throw new IllegalArgumentException("IP2Location API key is required.");
        }
        String normalized = value.trim();
        if (normalized.length() < 8
                || normalized.length() > 256
                || !normalized.matches("^[\\x21-\\x7E]+$")
                || normalized.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("IP2Location API key format is invalid.");
        }
        return normalized;
    }

    private static String mask(String value) {
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4)
                + "****"
                + value.substring(value.length() - 4);
    }

    private static byte[] decodeUrl(String value) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (!Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(decoded)
                    .equals(value)) {
                throw new IllegalArgumentException("Non-canonical Base64URL.");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Encrypted API key envelope is invalid.", exception);
        }
    }

    private static String encodeUrl(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
