package com.example.temperate.common.redis.key;

import java.util.regex.Pattern;

/**
 * 表示由专用对称密钥生成、可安全放入 Redis Key 的固定长度加密资源标识。
 *
 * <p>该值对象只校验二十二字符 Base64URL 规范，不负责执行加解密；强类型边界用于阻止业务代码把明文
 * {@code long} 或任意字符串误传给需要加密标识的 Key 工厂。</p>
 */
public record EncryptedRedisId(String value) {

    public static final int ENCODED_LENGTH = 22;
    private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9_-]{22}$");

    public EncryptedRedisId {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Encrypted Redis ID must be 22-character Base64URL without padding.");
        }
    }
}
