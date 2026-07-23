package com.example.temperate.service.registration.dto.result;

import com.example.temperate.service.registration.enums.RegistrationStatus;
import java.time.Instant;

/**
 * 返回注册流程状态、到期信息及经服务端规范化的验证码投递目标。
 *
 * <p>邮箱和手机号来自受保护的 Redis 注册流程快照，仅供 Web 层在人机验证通过后映射到禁缓存响应；
 * 该对象不负责决定客户端是否可以看到联系方式。</p>
 */
public record RegistrationStatusResult(
        RegistrationStatus status,
        boolean humanVerified,
        boolean emailVerified,
        boolean phoneVerified,
        Instant createdAt,
        Instant expiresAt,
        Instant absoluteExpiresAt,
        String email,
        String phoneE164) {

    public RegistrationStatusResult(
            RegistrationStatus status,
            boolean humanVerified,
            boolean emailVerified,
            boolean phoneVerified,
            Instant createdAt,
            Instant expiresAt) {
        this(status, humanVerified, emailVerified, phoneVerified, createdAt, expiresAt,
                createdAt == null ? null : createdAt.plusSeconds(1800), null, null);
    }
}
