package com.example.temperate.service.registration.flow.domain;

import java.time.Instant;

/**
 * 表示从 Redis 注册流程存储读取并校验后的状态快照。
 */
public record RegistrationFlowSnapshot(
        String email,
        String phone,
        boolean humanVerified,
        boolean emailVerified,
        boolean phoneVerified,
        boolean completing,
        Instant createdAt,
        Instant expiresAt,
        Instant absoluteExpiresAt) {

    public RegistrationFlowSnapshot(
            String email,
            String phone,
            boolean humanVerified,
            boolean emailVerified,
            boolean phoneVerified,
            boolean completing,
            Instant createdAt,
            Instant expiresAt) {
        this(email, phone, humanVerified, emailVerified, phoneVerified, completing,
                createdAt, expiresAt,
                createdAt == null ? null : createdAt.plusSeconds(1800));
    }

    public boolean readyToComplete() {
        return humanVerified && emailVerified && phoneVerified && !completing;
    }
}
