package com.example.temperate.service.admin.login;

import com.example.temperate.common.security.hmac.HmacIdentifier;

/**
 * 表示管理员登录 Flow 在 Redis 中使用的用途隔离 HMAC 标识集合。
 */
public record ProtectedAdminLoginAccess(
        HmacIdentifier flowId,
        HmacIdentifier flowCsrfId,
        HmacIdentifier challengeId,
        HmacIdentifier deviceId) {
}
