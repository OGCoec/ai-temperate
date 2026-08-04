package com.example.temperate.service.auth.totp.management.dto;

import java.time.Instant;

/**
 * 表示只在当次设置响应中交付的待确认 TOTP 二维码材料。
 */
public record TotpSetupResult(
        String setupToken,
        String secretBase32,
        String otpauthUri,
        Instant expiresAt) {
}
