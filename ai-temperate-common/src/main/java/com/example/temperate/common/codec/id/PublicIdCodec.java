package com.example.temperate.common.codec.id;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * 将内部正数 Long ID 与固定长度 Base64URL 公共 ID 相互转换。
 *
 * <p>该类统一资源标识的外部表示并校验规范编码；它不提供保密或授权能力，调用方仍必须执行认证和
 * 资源级权限校验。</p>
 */
public final class PublicIdCodec {

    public static final int ENCODED_LENGTH = 11;
    public static final String ENCODED_PATTERN = "^[A-Za-z0-9_-]{11}$";

    private static final Pattern VALID_FORMAT = Pattern.compile(ENCODED_PATTERN);
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public String encode(long internalId) {
        if (internalId <= 0) {
            throw new IllegalArgumentException("Internal ID must be positive.");
        }
        return ENCODER.encodeToString(ByteBuffer.allocate(Long.BYTES).putLong(internalId).array());
    }

    public long decode(String publicId) {
        if (publicId == null || !VALID_FORMAT.matcher(publicId).matches()) {
            throw new IllegalArgumentException("Public ID must be 11-character Base64URL without padding.");
        }

        byte[] decoded;
        try {
            decoded = DECODER.decode(publicId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Public ID is not valid Base64URL.", exception);
        }
        if (decoded.length != Long.BYTES) {
            throw new IllegalArgumentException("Public ID must decode to eight bytes.");
        }

        long internalId = ByteBuffer.wrap(decoded).getLong();
        if (internalId <= 0) {
            throw new IllegalArgumentException("Decoded public ID must be positive.");
        }
        // 回编码必须与原文完全一致，拒绝同一内部 ID 的非规范别名，保证路由与缓存键的一致性。
        if (!encode(internalId).equals(publicId)) {
            throw new IllegalArgumentException("Public ID is not canonically encoded.");
        }
        return internalId;
    }
}
