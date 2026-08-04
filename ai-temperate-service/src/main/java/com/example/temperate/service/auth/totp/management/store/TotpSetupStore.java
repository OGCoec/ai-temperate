package com.example.temperate.service.auth.totp.management.store;

import com.example.temperate.service.auth.totp.management.TotpManagementAction;
import java.time.Duration;
import java.time.Instant;

/**
 * 定义每用户唯一待确认 TOTP 新密钥在 Redis 中的保存、校验、失败计数和删除边界。
 */
public interface TotpSetupStore {

    void save(
            long userId,
            String rawSetupToken,
            String deviceInstallationId,
            String encryptedSecret,
            TotpManagementAction action,
            boolean expectedEnabled,
            String expectedEncryptedSecret,
            Instant createdAt,
            Duration ttl);

    TotpSetupSnapshot getRequired(
            long userId,
            String rawSetupToken,
            String deviceInstallationId,
            Instant now);

    int recordFailure(
            long userId,
            String rawSetupToken,
            String deviceInstallationId,
            Instant now);

    void delete(long userId, String rawSetupToken);

    void deleteForUser(long userId);
}
