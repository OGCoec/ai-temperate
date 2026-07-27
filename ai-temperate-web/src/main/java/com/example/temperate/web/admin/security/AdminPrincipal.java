package com.example.temperate.web.admin.security;

/**
 * 表示写入 Spring SecurityContext 的唯一管理员公开身份。
 *
 * <p>Principal 不包含密码哈希、原始会话 Token 或设备原始标识。</p>
 */
public record AdminPrincipal(
        String email,
        String countryIso2,
        String phoneE164) {

    @Override
    public String toString() {
        return "AdminPrincipal[redacted]";
    }
}
