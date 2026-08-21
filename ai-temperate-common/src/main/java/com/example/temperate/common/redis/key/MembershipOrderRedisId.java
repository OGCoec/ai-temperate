package com.example.temperate.common.redis.key;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import java.util.regex.Pattern;

/**
 * 该值类型是来约束只能把规范 22 字符会员订单 Base64URL 传入会员支付 Redis KeyFactory 方法。
 *
 * <p>格式校验不代表资源授权，用户查询仍必须校验订单归属。</p>
 */
public record MembershipOrderRedisId(String value) {

    private static final Pattern FORMAT = Pattern.compile(HybridBase64UrlCodec.ENCODED_PATTERN);
    private static final HybridBase64UrlCodec CODEC = new HybridBase64UrlCodec();

    public MembershipOrderRedisId {
        if (value == null || !FORMAT.matcher(value).matches() || !canonical(value)) {
            throw new IllegalArgumentException(
                    "Membership order Redis ID must be a canonical 22-character Base64URL value.");
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
