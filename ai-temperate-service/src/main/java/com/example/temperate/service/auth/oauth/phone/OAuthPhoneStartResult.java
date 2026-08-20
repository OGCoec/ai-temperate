package com.example.temperate.service.auth.oauth.phone;

import java.time.Instant;

/**
 * 表示 OAuth 手机验证码子流程需要交付客户端的短时 Token、Challenge 和过期时间。
 */
public record OAuthPhoneStartResult(
        String rawPhoneFlowToken,
        String challengeHandle,
        Instant expiresAt) {
}
