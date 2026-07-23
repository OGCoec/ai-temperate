package com.example.temperate.service.auth.passwordreset.dto;

/**
 * 承载访问密码重置流程所需的原始流程、挑战、设备和客户端网络上下文。
 *
 * <p>流程与挑战凭据仅在短生命周期重置链路中使用，禁止记录或作为长期身份标识。</p>
 */
public record PasswordResetAccess(
        String resetFlowToken,
        String challengeHandle,
        String deviceInstallationId,
        String clientIp) {
}
