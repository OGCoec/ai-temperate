package com.example.temperate.common.codec.id;

import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * 将混合工作器生成的 128 位二进制 ID 转换为固定 22 字符的规范 Base64URL 资源 ID。
 *
 * <p>该编解码器只负责无填充 URL 安全编码和规范性校验，不提供加密或资源授权；调用方仍必须执行身份认证与资源级授权。</p>
 */
public final class HybridBase64UrlCodec {

    public static final int BINARY_LENGTH = 16;
    public static final int ENCODED_LENGTH = 22;
    public static final String ENCODED_PATTERN = "^[A-Za-z0-9_-]{22}$";
    public static final String FORMAT = ENCODED_PATTERN;

    private static final Pattern FORMAT_PATTERN = Pattern.compile(FORMAT);
    private static final Base64.Encoder ENCODER =
            Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public String encode(byte[] value) {
        if (value == null || value.length != BINARY_LENGTH) {
            throw new IllegalArgumentException(
                    "Hybrid ID must contain exactly 16 bytes.");
        }
        return ENCODER.encodeToString(value);
    }

    public byte[] decode(String value) {
        if (value == null
                || value.length() != ENCODED_LENGTH
                || !FORMAT_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Hybrid public ID must be a 22-character Base64URL value.");
        }
        final byte[] decoded;
        try {
            decoded = DECODER.decode(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Hybrid public ID is not valid Base64URL.", exception);
        }
        // 解码后必须重新编码并逐字符相等，拒绝末尾未使用位不为零的非规范表示。
        if (decoded.length != BINARY_LENGTH
                || !Arrays.equals(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        encode(decoded).getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException(
                    "Hybrid public ID is not canonically encoded.");
        }
        return decoded;
    }
}
