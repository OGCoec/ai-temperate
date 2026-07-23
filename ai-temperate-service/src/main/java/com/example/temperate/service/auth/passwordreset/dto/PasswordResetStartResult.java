package com.example.temperate.service.auth.passwordreset.dto;

import java.time.Instant;

/**
 * 返回新建密码重置流程的访问凭据和到期时间。
 */
public record PasswordResetStartResult(
        String resetFlowToken,
        String challengeHandle,
        Instant expiresAt) {
}
