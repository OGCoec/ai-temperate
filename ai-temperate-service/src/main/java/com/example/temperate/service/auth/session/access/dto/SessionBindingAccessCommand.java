package com.example.temperate.service.auth.session.access.dto;

import com.example.temperate.common.security.hmac.HmacIdentifier;

/**
 * 表示 WebSocket 握手对现有 Refresh Session 执行只读绑定校验所需的受保护参数。
 */
public record SessionBindingAccessCommand(
        long expectedUserId,
        HmacIdentifier refreshSessionDigest,
        HmacIdentifier deviceDigest) {

    public SessionBindingAccessCommand {
        if (expectedUserId <= 0 || refreshSessionDigest == null || deviceDigest == null) {
            throw new IllegalArgumentException("Session binding access command is invalid.");
        }
    }
}
