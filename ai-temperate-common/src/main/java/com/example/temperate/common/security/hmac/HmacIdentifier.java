package com.example.temperate.common.security.hmac;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 表示经过 HMAC-SHA-256 保护且采用 Base64URL 编码的稳定标识。
 *
 * <p>该值可安全用于 Redis 键和关联查询，避免直接传播低熵邮箱、手机号或原始令牌；它不保存原始标识，
 * `toString()` 也始终脱敏。</p>
 */
public final class HmacIdentifier {

    private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final String value;

    HmacIdentifier(String value) {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("HMAC identifier must be a SHA-256 Base64URL value.");
        }
        this.value = value;
    }

    /**
     * 从已经完成 HMAC 保护的 Base64URL 文本恢复值对象，不执行新的哈希计算。
     */
    public static HmacIdentifier fromProtectedValue(String value) {
        return new HmacIdentifier(value);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof HmacIdentifier that && value.equals(that.value));
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return "HmacIdentifier[redacted]";
    }
}
