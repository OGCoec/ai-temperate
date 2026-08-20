package com.example.temperate.service.auth.oauth.flow;

import com.example.temperate.common.security.hmac.HmacIdentifier;

/**
 * 表示 OAuth Flow Token、设备和 IP 经用途隔离 HMAC 后的 Redis 访问材料。
 */
public record ProtectedOAuthFlowAccess(
        HmacIdentifier flowId,
        HmacIdentifier deviceId,
        HmacIdentifier globalDeviceId,
        HmacIdentifier clientIpId) {
}
