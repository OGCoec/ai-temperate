package com.example.temperate.common.redis.key;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import java.util.regex.Pattern;

/**
 * 表示已经规范编码、只可用于异步 Generation Redis Key 的 22 字符公共标识。
 *
 * <p>独立类型用于避免把会话 ID 与生成 ID 在缓存边界误用；该格式校验不替代资源归属授权。</p>
 */
public record GenerationRedisId(String value) {

    private static final Pattern FORMAT =
            Pattern.compile(HybridBase64UrlCodec.ENCODED_PATTERN);

    public GenerationRedisId {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Generation Redis ID must be 22-character Base64URL.");
        }
    }
}
