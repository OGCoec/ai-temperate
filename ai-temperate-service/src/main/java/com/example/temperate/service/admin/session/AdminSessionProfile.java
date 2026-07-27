package com.example.temperate.service.admin.session;

import java.time.Instant;

/**
 * 向 Web 层返回管理员公开身份和当前滑动会话到期时间。
 */
public record AdminSessionProfile(
        String email,
        String countryIso2,
        String phoneE164,
        Instant expiresAt) {

    @Override
    public String toString() {
        return "AdminSessionProfile[redacted]";
    }
}
