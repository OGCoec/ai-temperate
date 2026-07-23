package com.example.temperate.service.auth.session.refresh.dto.command;

import com.example.temperate.common.security.hmac.HmacIdentifier;

/**
 * 承载创建刷新会话所需的用户快照及受保护认证标识。
 *
 * <p>RT、设备和 CSRF 字段均为 HMAC 标识；原始认证材料不得进入该存储命令。</p>
 */
public record NewRefreshSession(
        long userId,
        String publicId,
        HmacIdentifier refreshTokenHash,
        HmacIdentifier deviceHash,
        HmacIdentifier csrfHash,
        String email,
        String phone) {
}
