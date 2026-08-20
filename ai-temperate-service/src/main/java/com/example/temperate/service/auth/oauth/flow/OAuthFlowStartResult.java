package com.example.temperate.service.auth.oauth.flow;

import java.time.Instant;

/**
 * 表示 OAuth 启动后供 Web 层按平台选择 Cookie 或 Header/响应体传输的短时材料。
 */
public record OAuthFlowStartResult(
        String rawFlowToken,
        String nonce,
        String launchTicket,
        Instant expiresAt,
        Instant absoluteExpiresAt) {
}
