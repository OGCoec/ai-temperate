package com.example.temperate.service.user.apikey.idempotency;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.UUID;

/**
 * 该摘要器是来把内部用户 ID 与客户端 UUIDv4 转换为用途隔离 HMAC，使 Redis 锁 Key 不泄露任一原始标识。
 */
public final class ApiKeyCreateIdempotencyHasher {

    private static final String PURPOSE = "api-key-create-lock:v1";

    private final HmacSha256Identifier hmac;

    public ApiKeyCreateIdempotencyHasher(byte[] secret) {
        this.hmac = new HmacSha256Identifier(secret);
    }

    /**
     * 用户 ID 与 UUID 使用固定宽度二进制编码共同参与 HMAC，确保不同用户不会竞争同一辅助锁。
     */
    public HmacIdentifier identify(long loginIdentityId, UUID idempotencyKey) {
        if (loginIdentityId <= 0 || idempotencyKey == null) {
            throw new IllegalArgumentException("API Key create lock input is invalid");
        }
        UUID key = Objects.requireNonNull(idempotencyKey);
        // 固定宽度大端序载荷消除字符串表示差异，并由 purpose 隔离其他使用同一根密钥的业务域。
        byte[] payload = ByteBuffer.allocate(Long.BYTES + Long.BYTES * 2)
                .putLong(loginIdentityId)
                .putLong(key.getMostSignificantBits())
                .putLong(key.getLeastSignificantBits())
                .array();
        return hmac.identify(PURPOSE, payload);
    }
}
