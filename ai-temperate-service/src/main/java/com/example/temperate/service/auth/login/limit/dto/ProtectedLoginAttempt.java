package com.example.temperate.service.auth.login.limit.dto;

import com.example.temperate.common.security.hmac.HmacIdentifier;

/**
 * 表示登录风控键所需的 HMAC 化主体、设备与 IP 标识。
 *
 * <p>该类型用于保护 Redis 风控键中的敏感输入，不保存原始邮箱、手机号、设备 ID 或 IP。</p>
 */
public record ProtectedLoginAttempt(
        HmacIdentifier identifierHash,
        HmacIdentifier actorHash,
        HmacIdentifier networkHash,
        HmacIdentifier globalDeviceHash) {
}
