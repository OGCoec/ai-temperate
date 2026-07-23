package com.example.temperate.service.auth.login.code.flow;

import com.example.temperate.common.security.hmac.HmacIdentifier;

/**
 * 封装登录验证码流程访问凭据的 HMAC 标识形式。
 *
 * <p>该类型用于在 Redis 键和值中绑定流程、挑战、设备和验证码，不保存可直接重放的原始凭据。</p>
 */
public record ProtectedLoginCodeAccess(
        HmacIdentifier flowId,
        HmacIdentifier challengeId,
        HmacIdentifier deviceHash,
        HmacIdentifier codeId) {
}
