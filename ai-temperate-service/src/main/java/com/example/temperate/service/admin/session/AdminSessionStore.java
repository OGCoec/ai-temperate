package com.example.temperate.service.admin.session;

import java.time.Duration;
import java.time.Instant;
import com.example.temperate.service.risk.preauth.domain.PreAuthSessionBinding;

/**
 * 定义 Redis 7.4 Hash Field 管理员会话的创建、滑动续期和撤销边界。
 */
public interface AdminSessionStore {

    AdminSession create(
            String rawToken,
            String deviceInstallationId,
            Instant now,
            Duration ttl,
            int maximumSessions);

    AdminSession touch(
            String rawToken,
            String deviceInstallationId,
            Instant now,
            Duration ttl);

    AdminSession touchWithPreAuth(
            String rawToken,
            String deviceInstallationId,
            Instant now,
            Duration ttl,
            PreAuthSessionBinding preAuthBinding);

    void delete(String rawToken);

    void deleteAll();
}
