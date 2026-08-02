package com.example.temperate.service.user.aiconversation.security;

import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * 将当前用户和 UUIDv4 幂等键组合后执行用途隔离的 HMAC-SHA256，数据库只保存 32 字节摘要。
 */
public final class AiConversationIdempotencyHasher {

    private static final String PURPOSE = "ai-conversation-idempotency:v1";
    private static final String CONCURRENCY_PURPOSE =
            "ai-conversation-concurrency-user:v1";
    private final HmacSha256Identifier hmac;

    public AiConversationIdempotencyHasher(byte[] secret) {
        this.hmac = new HmacSha256Identifier(secret);
    }

    public byte[] digest(long userId, UUID idempotencyKey) {
        if (userId <= 0 || idempotencyKey == null) {
            throw new IllegalArgumentException(
                    "AI conversation idempotency input is invalid.");
        }
        byte[] keyBytes = idempotencyKey.toString()
                .getBytes(StandardCharsets.US_ASCII);
        ByteBuffer payload =
                ByteBuffer.allocate(Long.BYTES + keyBytes.length);
        payload.putLong(userId).put(keyBytes);
        return Base64.getUrlDecoder().decode(
                hmac.identify(PURPOSE, payload.array()).value());
    }

    /**
     * 返回可用于内存注册表和 Redis Key 的受保护请求标识，不传播原始 UUID 或用户 ID。
     */
    public HmacIdentifier identifier(long userId, UUID idempotencyKey) {
        return HmacIdentifier.fromProtectedValue(
                Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(digest(userId, idempotencyKey)));
    }

    public HmacIdentifier concurrencyUserIdentifier(long userId) {
        if (userId <= 0L) {
            throw new IllegalArgumentException(
                    "AI conversation concurrency user ID is invalid.");
        }
        return hmac.identify(
                CONCURRENCY_PURPOSE,
                ByteBuffer.allocate(Long.BYTES).putLong(userId).array());
    }
}
