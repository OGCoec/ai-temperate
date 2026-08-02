package com.example.temperate.common.redis.key;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import java.util.regex.Pattern;

/**
 * 表示已经规范编码、可以安全用于 Redis Key 的 22 字符 AI 会话公共标识。
 *
 * <p>该类型只保证编码格式，不提供授权能力；业务仍必须校验会话属于当前用户。</p>
 */
public record ConversationRedisId(String value) {

    private static final Pattern FORMAT =
            Pattern.compile(HybridBase64UrlCodec.ENCODED_PATTERN);

    public ConversationRedisId {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Conversation Redis ID must be 22-character Base64URL.");
        }
    }
}
