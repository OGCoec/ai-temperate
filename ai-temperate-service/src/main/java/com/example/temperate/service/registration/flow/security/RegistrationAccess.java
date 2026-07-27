package com.example.temperate.service.registration.flow.security;

/**
 * 承载访问注册流程所需的原始流程 Token、CSRF、挑战、设备与网络上下文。
 *
 * <p>这些材料仅在服务端边界校验中使用，禁止作为持久化数据、日志字段或长期身份标识。</p>
 */
public record RegistrationAccess(
        String registerToken,
        String flowCsrf,
        String challengeHandle,
        String deviceInstallationId,
        String canonicalIp) {

    @Override
    public String toString() {
        return "RegistrationAccess[redacted]";
    }
}
