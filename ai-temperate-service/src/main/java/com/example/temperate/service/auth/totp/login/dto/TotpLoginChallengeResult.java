package com.example.temperate.service.auth.totp.login.dto;

import java.time.Instant;

/**
 * 表示第一因子通过后交付给传输层的短期 TOTP 登录挑战。
 */
public record TotpLoginChallengeResult(
        String rawFlowToken,
        Instant expiresAt,
        int attemptsRemaining) {
}
