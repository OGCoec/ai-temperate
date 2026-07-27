package com.example.temperate.service.risk.ip2location.domain;

import java.time.Instant;

/**
 * 表示仅存在于受控内存和 AES-GCM 明文中的 IP2Location 凭据及管理元数据。
 */
public record Ip2LocationKeyMaterial(
        int schemaVersion,
        String apiKey,
        String maskedKey,
        Ip2LocationPlanType planType,
        Instant createdAt,
        Instant expiresAt) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    @Override
    public String toString() {
        return "Ip2LocationKeyMaterial[redacted]";
    }
}
