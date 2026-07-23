package com.example.temperate.service.registration.flow.security;

import com.example.temperate.common.security.hmac.HmacIdentifier;

/**
 * 注册流程外部访问材料的受保护摘要集合。
 *
 * <p>用途：为 Redis 状态机提供流程、流程 CSRF、挑战、设备、IP 及双通道验证码的稳定索引；原始令牌和验证码
 * 不进入 Redis Key 或状态值。</p>
 */
public record ProtectedRegistrationAccess(
        HmacIdentifier flowId,
        HmacIdentifier flowCsrfHash,
        HmacIdentifier challengeId,
        HmacIdentifier deviceHash,
        HmacIdentifier globalDeviceHash,
        HmacIdentifier ipHash,
        HmacIdentifier emailCodeId,
        HmacIdentifier phoneCodeId) {
}
