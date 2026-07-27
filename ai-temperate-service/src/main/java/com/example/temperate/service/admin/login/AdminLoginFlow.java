package com.example.temperate.service.admin.login;

import java.time.Instant;

/**
 * 表示管理员登录前创建的十分钟一次性 Flow 及其受保护访问绑定。
 */
public record AdminLoginFlow(
        ProtectedAdminLoginAccess access,
        Instant createdAt,
        Instant expiresAt) {
}
