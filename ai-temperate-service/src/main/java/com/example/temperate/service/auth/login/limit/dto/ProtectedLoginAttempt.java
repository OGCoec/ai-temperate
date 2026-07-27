package com.example.temperate.service.auth.login.limit.dto;

import com.example.temperate.common.security.hmac.HmacIdentifier;

/**
 * 表示登录限流键实际使用的 HMAC 化主体与设备标识。
 *
 * <p>该类型用于保护 Redis 限流键中的敏感输入，不保存原始邮箱、手机号或设备 ID。</p>
 */
public record ProtectedLoginAttempt(
        HmacIdentifier identifierHash,
        HmacIdentifier actorHash,
        HmacIdentifier globalDeviceHash) {
}
