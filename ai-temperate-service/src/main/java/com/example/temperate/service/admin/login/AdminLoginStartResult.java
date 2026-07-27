package com.example.temperate.service.admin.login;

import java.time.Instant;

/**
 * 返回管理员登录 Flow 的原始短期材料；H5 传输层必须改写为安全 Cookie。
 */
public record AdminLoginStartResult(
        String flowToken,
        String flowCsrf,
        String challengeId,
        Instant expiresAt) {

    @Override
    public String toString() {
        return "AdminLoginStartResult[redacted]";
    }
}
