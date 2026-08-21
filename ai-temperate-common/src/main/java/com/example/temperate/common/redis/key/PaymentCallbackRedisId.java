package com.example.temperate.common.redis.key;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import java.util.regex.Pattern;

/**
 * 该值类型是来约束只能把规范 22 字符支付回调 Base64URL 传入回调数据 Redis KeyFactory 方法。
 *
 * <p>独立类型防止订单 ID 与回调 ID 在状态机边界被误用。</p>
 */
public record PaymentCallbackRedisId(String value) {

    private static final Pattern FORMAT = Pattern.compile(HybridBase64UrlCodec.ENCODED_PATTERN);
    private static final HybridBase64UrlCodec CODEC = new HybridBase64UrlCodec();

    public PaymentCallbackRedisId {
        if (value == null || !FORMAT.matcher(value).matches() || !canonical(value)) {
            throw new IllegalArgumentException(
                    "Payment callback Redis ID must be a canonical 22-character Base64URL value.");
        }
    }

    private static boolean canonical(String value) {
        try {
            byte[] decoded = CODEC.decode(value);
            int aggregate = 0;
            for (byte current : decoded) {
                aggregate |= current;
            }
            return aggregate != 0 && CODEC.encode(decoded).equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
