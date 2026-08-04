package com.example.temperate.service.auth.totp.verification.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.login.enums.LoginErrorCode;
import com.example.temperate.service.auth.login.exception.LoginException;
import com.example.temperate.service.auth.totp.verification.TotpTimeStepReplayStore;
import java.time.Duration;
import java.util.Objects;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 使用 Redis SET NX 原子领取 TOTP 时间片，供轮换和关闭等当前密钥验证防止重放。
 */
@Component
public final class RedisTotpTimeStepReplayStore implements TotpTimeStepReplayStore {

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;

    public RedisTotpTimeStepReplayStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
    }

    @Override
    public boolean claim(HmacIdentifier replayId, Duration ttl) {
        if (replayId == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw unavailable(null);
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                    keyFactory.totpUsedTimeStepKey(replayId), "1", ttl));
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private static LoginException unavailable(Throwable cause) {
        return new LoginException(
                LoginErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                "TOTP replay protection is unavailable.",
                cause);
    }
}
