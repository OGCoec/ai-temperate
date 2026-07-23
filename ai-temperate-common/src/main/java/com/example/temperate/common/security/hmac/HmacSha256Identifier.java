package com.example.temperate.common.security.hmac;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * 使用服务端密钥将已规范化的敏感标识转换为 HMAC-SHA-256 标识。
 *
 * <p>该类用于防止 Redis 键和索引暴露可枚举的原始数据；它只接受已规范化输入，输入格式归一化仍由调用方负责。</p>
 */
public final class HmacSha256Identifier {

    public static final int MINIMUM_SECRET_BYTES = 32;

    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final byte[] secret;

    public HmacSha256Identifier(byte[] secret) {
        if (secret == null || secret.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalArgumentException("HMAC secret must contain at least 32 bytes.");
        }
        this.secret = secret.clone();
    }

    public HmacIdentifier identify(String normalizedIdentifier) {
        if (normalizedIdentifier == null || normalizedIdentifier.isBlank()) {
            throw new IllegalArgumentException("Normalized identifier must not be blank.");
        }
        try {
            // 每次创建独立 Mac，避免共享可变密码学对象在并发调用时污染计算状态。
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            byte[] digest = mac.doFinal(normalizedIdentifier.getBytes(StandardCharsets.UTF_8));
            return new HmacIdentifier(ENCODER.encodeToString(digest));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable.", exception);
        }
    }
}
