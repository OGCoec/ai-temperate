package com.example.temperate.service.auth.login.code.dto;

import java.time.Instant;

/**
 * 表示 OAuth 手机证明子流程的短时凭据和服务端规范化 E.164 手机号。
 *
 * <p>规范手机号只供 OAuth Flow 锁定使用，Web 层响应不得原样回显。</p>
 */
public record OAuthPhoneCodeStartResult(
        String rawFlowToken,
        String challengeHandle,
        String normalizedPhone,
        Instant expiresAt) {
}
