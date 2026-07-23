package com.example.temperate.service.auth.passwordreset.flow;

import com.example.temperate.common.security.hmac.HmacIdentifier;

/**
 * 封装密码重置流程访问凭据及目标标识的 HMAC 形式。
 *
 * <p>该类型用于安全构造 Redis 流程、验证码和风险控制键，不保留可直接重放的原始数据。</p>
 */
public record ProtectedPasswordResetAccess(
        HmacIdentifier flowId,
        HmacIdentifier challengeId,
        HmacIdentifier deviceHash,
        HmacIdentifier globalDeviceHash,
        HmacIdentifier codeId,
        HmacIdentifier targetHash) {
}
