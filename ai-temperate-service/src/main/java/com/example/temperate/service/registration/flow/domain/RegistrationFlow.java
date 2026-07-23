package com.example.temperate.service.registration.flow.domain;

import com.example.temperate.service.registration.flow.security.ProtectedRegistrationAccess;
import java.time.Instant;

/**
 * 表示注册状态机在服务层流转时使用的完整流程领域对象。
 */
public record RegistrationFlow(
        int schemaVersion,
        String email,
        String phone,
        ProtectedRegistrationAccess access,
        Instant createdAt,
        Instant expiresAt,
        Instant absoluteExpiresAt) {

    public static final int CURRENT_SCHEMA_VERSION = 2;

    public RegistrationFlow(
            int schemaVersion,
            String email,
            String phone,
            ProtectedRegistrationAccess access,
            Instant createdAt,
            Instant expiresAt) {
        this(schemaVersion, email, phone, access, createdAt, expiresAt,
                createdAt == null ? null : createdAt.plusSeconds(1800));
    }
}
