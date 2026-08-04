package com.example.temperate.service.auth.totp.login.store;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import java.time.Duration;
import java.time.Instant;

/**
 * 定义 TOTP 登录挑战在 Redis 中创建、失败计数和一次性成功消费的原子状态边界。
 */
public interface TotpLoginChallengeStore {

    void create(
            HmacIdentifier flowId,
            HmacIdentifier deviceId,
            long userId,
            Instant createdAt,
            Duration ttl);

    TotpLoginChallengeSnapshot getRequired(
            HmacIdentifier flowId,
            HmacIdentifier deviceId,
            Instant now);

    int recordFailure(
            HmacIdentifier flowId,
            HmacIdentifier deviceId,
            Instant now);

    void consumeSuccessful(
            HmacIdentifier flowId,
            HmacIdentifier deviceId,
            HmacIdentifier replayId,
            Instant now);
}
