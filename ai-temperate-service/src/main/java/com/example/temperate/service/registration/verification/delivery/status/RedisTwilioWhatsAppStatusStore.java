package com.example.temperate.service.registration.verification.delivery.status;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * 使用 Redis Lua 原子维护 Twilio Message 状态索引，防止乱序回调把新状态回退成旧状态。
 *
 * <p>Redis 键只使用注册域 HMAC 后的 SID，值只保存操作标识、低基数状态和时间，不保存原始手机号或消息正文。
 */
@Service
public final class RedisTwilioWhatsAppStatusStore implements TwilioWhatsAppStatusStore {

    private static final RedisScript<Long> RECORD = script();
    private static final String SID_NAMESPACE = "twilio-message:";
    private static final int MAX_TTL_SECONDS = 86_400;

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final HmacSha256Identifier hmacIdentifier;

    public RedisTwilioWhatsAppStatusStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            @Qualifier("registrationHmacIdentifier") HmacSha256Identifier hmacIdentifier) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.hmacIdentifier = Objects.requireNonNull(hmacIdentifier);
    }

    @Override
    public void recordAccepted(
            String providerMessageId,
            String operationId,
            String providerStatus,
            Instant acceptedAt,
            Duration ttl) {
        execute(providerMessageId, operationId, providerStatus, "", acceptedAt, ttl,
                rank(providerStatus), true);
    }

    @Override
    public boolean recordCallback(
            String providerMessageId,
            String providerStatus,
            String providerErrorCode,
            Instant receivedAt,
            Duration ttl) {
        return execute(providerMessageId, "", providerStatus, providerErrorCode,
                receivedAt, ttl, rank(providerStatus), false) == 1L;
    }

    private long execute(
            String sid,
            String operationId,
            String status,
            String errorCode,
            Instant at,
            Duration ttl,
            int statusRank,
            boolean allowCreate) {
        String validSid = requireSafeSid(sid);
        String validStatus = requireDiagnostic(status, "status");
        String validOperationId = operationId == null || operationId.isBlank()
                ? "" : requireDiagnostic(operationId, "operationId");
        String validErrorCode = errorCode == null || errorCode.isBlank()
                ? "" : requireDiagnostic(errorCode, "errorCode");
        long seconds = ttl == null ? 900L : Math.max(1L, Math.min(MAX_TTL_SECONDS, ttl.toSeconds()));
        if (at == null) {
            throw new IllegalArgumentException("timestamp must not be null");
        }
        HmacIdentifier sidHash = hmacIdentifier.identify(SID_NAMESPACE + validSid);
        Long result = redisTemplate.execute(
                RECORD,
                List.of(keyFactory.twilioMessageStatusKey(sidHash)),
                validOperationId,
                validStatus,
                validErrorCode,
                Long.toString(at.getEpochSecond()),
                Long.toString(seconds),
                Integer.toString(statusRank),
                allowCreate ? "1" : "0");
        if (result == null) {
            throw new IllegalStateException("Twilio status index returned no result");
        }
        return result;
    }

    private static int rank(String status) {
        return switch (status == null ? "" : status.toLowerCase(java.util.Locale.ROOT)) {
            case "accepted" -> 10;
            case "queued" -> 20;
            case "sending" -> 30;
            case "sent" -> 40;
            case "delivered" -> 50;
            case "read" -> 60;
            case "failed", "undelivered" -> 70;
            default -> 5;
        };
    }

    private static String requireSafeSid(String sid) {
        if (sid == null || !sid.matches("^(SM|MM)[0-9a-fA-F]{32}$")) {
            throw new IllegalArgumentException("providerMessageId is invalid");
        }
        return sid;
    }

    private static String requireDiagnostic(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 128
                || !value.matches("^[A-Za-z0-9._:-]+$")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static RedisScript<Long> script() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/twilio/record_twilio_message_status.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
