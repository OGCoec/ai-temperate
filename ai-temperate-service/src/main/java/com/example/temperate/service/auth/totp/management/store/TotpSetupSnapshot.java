package com.example.temperate.service.auth.totp.management.store;

import com.example.temperate.service.auth.totp.management.TotpManagementAction;
import java.time.Instant;

/**
 * 表示已通过用户、设备和 setupToken 校验的 Redis 待确认 TOTP 密钥快照。
 */
public record TotpSetupSnapshot(
        String encryptedSecret,
        TotpManagementAction action,
        boolean expectedEnabled,
        String expectedEncryptedSecret,
        int failedAttempts,
        Instant expiresAt) {
}
