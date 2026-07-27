package com.example.temperate.service.risk.webrtc.security;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.domain.RiskScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 使用独立 AES-256-GCM 密钥保护 PreAuth 中的完整 WebRTC IP 集合。
 *
 * <p>AAD 同时绑定作用域、PreAuth Token 摘要和当前 HTTP IP 摘要，密文不能跨用户/管理员、
 * Token 或网络出口复用；该组件不负责记录、展示或规范化 IP。</p>
 */
public final class WebRtcIpProtector {

    private static final String VERSION = "v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final TypeReference<List<String>> STRING_LIST =
            new TypeReference<>() { };

    private final SecretKeySpec key;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom;

    public WebRtcIpProtector(String canonicalBase64Key, ObjectMapper objectMapper) {
        this(canonicalBase64Key, objectMapper, new SecureRandom());
    }

    WebRtcIpProtector(
            String canonicalBase64Key,
            ObjectMapper objectMapper,
            SecureRandom secureRandom) {
        this.key = new SecretKeySpec(
                decodeCanonicalKey(canonicalBase64Key),
                "AES");
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.secureRandom = Objects.requireNonNull(secureRandom);
    }

    public String encrypt(
            List<String> webRtcIps,
            RiskScope scope,
            HmacIdentifier preAuthTokenDigest,
            HmacIdentifier currentIpDigest) {
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(scope, preAuthTokenDigest, currentIpDigest));
            byte[] plaintext = objectMapper.writeValueAsBytes(List.copyOf(webRtcIps));
            byte[] encrypted = cipher.doFinal(plaintext);
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return VERSION
                    + "."
                    + encoder.encodeToString(iv)
                    + "."
                    + encoder.encodeToString(encrypted);
        } catch (GeneralSecurityException | RuntimeException
                | com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new WebRtcIpProtectionException(exception);
        }
    }

    public List<String> decrypt(
            String protectedValue,
            RiskScope scope,
            HmacIdentifier preAuthTokenDigest,
            HmacIdentifier currentIpDigest) {
        try {
            String[] parts = protectedValue == null
                    ? new String[0]
                    : protectedValue.split("\\.", -1);
            if (parts.length != 3 || !VERSION.equals(parts[0])) {
                throw new WebRtcIpProtectionException();
            }
            byte[] iv = decodeCanonicalUrl(parts[1]);
            byte[] encrypted = decodeCanonicalUrl(parts[2]);
            if (iv.length != IV_BYTES || encrypted.length <= TAG_BITS / Byte.SIZE) {
                throw new WebRtcIpProtectionException();
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(scope, preAuthTokenDigest, currentIpDigest));
            byte[] plaintext = cipher.doFinal(encrypted);
            List<String> result = objectMapper.readValue(plaintext, STRING_LIST);
            return result == null ? List.of() : List.copyOf(result);
        } catch (WebRtcIpProtectionException exception) {
            throw exception;
        } catch (GeneralSecurityException | RuntimeException
                | java.io.IOException exception) {
            throw new WebRtcIpProtectionException(exception);
        }
    }

    private static byte[] aad(
            RiskScope scope,
            HmacIdentifier preAuthTokenDigest,
            HmacIdentifier currentIpDigest) {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(preAuthTokenDigest);
        Objects.requireNonNull(currentIpDigest);
        return ("webRtcIps|"
                        + scope.name()
                        + "|"
                        + preAuthTokenDigest.value()
                        + "|"
                        + currentIpDigest.value())
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] decodeCanonicalKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("WebRTC IP encryption key is required.");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length != 32
                    || !Base64.getEncoder().encodeToString(decoded).equals(value)) {
                throw new IllegalArgumentException(
                        "WebRTC IP encryption key must be canonical Base64 for 32 bytes.");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "WebRTC IP encryption key must be canonical Base64 for 32 bytes.",
                    exception);
        }
    }

    private static byte[] decodeCanonicalUrl(String value) {
        if (value == null || value.isBlank() || value.indexOf('=') >= 0) {
            throw new WebRtcIpProtectionException();
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (!Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(decoded)
                    .equals(value)) {
                throw new WebRtcIpProtectionException();
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new WebRtcIpProtectionException(exception);
        }
    }
}
