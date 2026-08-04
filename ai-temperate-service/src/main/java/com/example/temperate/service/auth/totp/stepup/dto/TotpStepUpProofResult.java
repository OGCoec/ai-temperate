package com.example.temperate.service.auth.totp.stepup.dto;

import java.time.Instant;

/**
 * 表示密码、邮箱码或短信码复验成功后签发的一次性敏感操作凭证。
 */
public record TotpStepUpProofResult(
        String stepUpToken,
        Instant expiresAt) {
}
