package com.example.temperate.service.auth.passwordreset.flow;

import com.example.temperate.service.registration.enums.VerificationChannel;
import java.time.Instant;

/**
 * 表示密码重置流程在存储层读取到的当前状态快照。
 *
 * <p>快照仅包含流程决策所需字段，不包含验证码、找回 Token 或其他可重放的原始凭据。</p>
 */
public record PasswordResetFlowSnapshot(
        VerificationChannel channel,
        String identifier,
        long userId,
        boolean humanVerified,
        Instant createdAt,
        Instant expiresAt,
        Instant absoluteExpiresAt) {
}
