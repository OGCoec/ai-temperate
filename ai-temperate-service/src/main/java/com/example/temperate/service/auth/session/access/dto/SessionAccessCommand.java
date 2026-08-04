package com.example.temperate.service.auth.session.access.dto;

/**
 * 承载普通用户受保护请求的 AT、RT、CSRF 与设备凭据，供统一会话编排服务执行 RT-first 认证。
 */
public record SessionAccessCommand(
        String accessToken,
        String refreshToken,
        String presentedCsrfToken,
        String deviceInstallationId) {

    @Override
    public String toString() {
        return "SessionAccessCommand[redacted]";
    }
}
