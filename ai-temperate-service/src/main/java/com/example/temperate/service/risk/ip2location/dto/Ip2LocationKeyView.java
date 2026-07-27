package com.example.temperate.service.risk.ip2location.dto;

import com.example.temperate.service.risk.ip2location.domain.Ip2LocationPlanType;
import java.time.Instant;

/**
 * 表示管理员可以查看的脱敏 IP2Location Key 元数据。
 */
public record Ip2LocationKeyView(
        String keyId,
        String maskedKey,
        Ip2LocationPlanType planType,
        long remainingQuota,
        Instant expiresAt,
        Instant createdAt) {
}
