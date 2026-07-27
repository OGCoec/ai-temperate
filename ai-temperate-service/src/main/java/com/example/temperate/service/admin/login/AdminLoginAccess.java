package com.example.temperate.service.admin.login;

/**
 * 承载管理员登录 Flow 的原始 Token、CSRF、Challenge、设备和规范客户端 IP。
 *
 * <p>原始材料仅用于当前服务端校验，禁止持久化或日志输出。</p>
 */
public record AdminLoginAccess(
        String flowToken,
        String flowCsrf,
        String challengeId,
        String deviceInstallationId,
        String canonicalIp) {

    @Override
    public String toString() {
        return "AdminLoginAccess[redacted]";
    }
}
