package com.example.temperate.service.user.apikey.credential.impl;

import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.example.temperate.service.user.apikey.credential.ApiKeyCredentialService;
import com.example.temperate.service.user.apikey.credential.GeneratedApiKey;
import com.example.temperate.service.user.apikey.credential.InvalidApiKeyFormatException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 该实现是来使用 SecureRandom 和单一 HMAC-SHA256 Secret 生成不可恢复凭证；每次新建 Mac 以避免单例服务共享非线程安全状态。
 */
@Service
public final class ApiKeyCredentialServiceImpl implements ApiKeyCredentialService {

    private static final Pattern API_KEY_PATTERN =
            Pattern.compile("^sk-[A-Za-z0-9_-]{86}$");
    private static final Base64.Encoder URL_ENCODER =
            Base64.getUrlEncoder().withoutPadding();
    private final SecureRandom secureRandom;
    private final SecretKeySpec hmacKey;

    @Autowired
    public ApiKeyCredentialServiceImpl(ApiKeyProperties properties) {
        this(properties, new SecureRandom());
    }

    ApiKeyCredentialServiceImpl(ApiKeyProperties properties, SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
        this.hmacKey = decodeSecret(properties);
    }

    @Override
    public GeneratedApiKey generate() {
        byte[] randomPayload = new byte[64];
        secureRandom.nextBytes(randomPayload);
        String plaintext = "sk-" + URL_ENCODER.encodeToString(randomPayload);
        String hint = plaintext.substring(plaintext.length() - 4);
        return new GeneratedApiKey(plaintext, digest(plaintext), hint, mask(hint));
    }

    @Override
    public byte[] digest(String plaintextApiKey) {
        if (plaintextApiKey == null || !API_KEY_PATTERN.matcher(plaintextApiKey).matches()) {
            throw new InvalidApiKeyFormatException();
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            // `sk-` 前缀和 86 字符载荷作为一个整体进入 HMAC，禁止只摘要随机部分。
            return mac.doFinal(plaintextApiKey.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    @Override
    public String digestIdentifier(byte[] digest) {
        if (digest == null || digest.length != 32) {
            throw new IllegalArgumentException("API Key digest must contain 32 bytes");
        }
        return URL_ENCODER.encodeToString(digest);
    }

    @Override
    public String mask(String keyHint) {
        if (keyHint == null || !keyHint.matches("[A-Za-z0-9_-]{4}")) {
            throw new IllegalArgumentException("API Key hint must contain four Base64URL characters");
        }
        return "sk-…" + keyHint;
    }

    private static SecretKeySpec decodeSecret(ApiKeyProperties properties) {
        String configured = properties.getHmacSecretBase64();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("API_KEY_HMAC_SECRET_BASE64 is required");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(configured);
            if (decoded.length < 32
                    || !Base64.getEncoder().encodeToString(decoded).equals(configured)) {
                throw new IllegalStateException(
                        "API_KEY_HMAC_SECRET_BASE64 must be canonical Base64 with at least 32 bytes");
            }
            return new SecretKeySpec(decoded, "HmacSHA256");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "API_KEY_HMAC_SECRET_BASE64 must be valid Base64", exception);
        }
    }
}
