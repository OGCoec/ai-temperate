package com.example.temperate.service.auth.login.code.dto;

/**
 * 表示访问登录验证码流程所需的短生命周期凭据和设备上下文。
 *
 * <p>其中流程凭据仅用于服务端校验，禁止记录或作为长期用户身份标识使用。</p>
 */
public record LoginCodeAccess(
        String flowToken,
        String challengeHandle,
        String deviceInstallationId,
        String clientIp) {
}
