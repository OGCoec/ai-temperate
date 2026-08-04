package com.example.temperate.service.auth.totp.login.store;

import java.time.Instant;

/**
 * 表示从 Redis 原子读取并完成设备绑定校验后的 TOTP 登录挑战快照。
 */
public record TotpLoginChallengeSnapshot(
        long userId,
        int failedAttempts,
        Instant expiresAt) {
}
