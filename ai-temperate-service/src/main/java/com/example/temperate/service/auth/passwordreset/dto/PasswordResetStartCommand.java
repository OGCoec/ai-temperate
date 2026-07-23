package com.example.temperate.service.auth.passwordreset.dto;

import com.example.temperate.service.registration.enums.VerificationChannel;

/**
 * 承载启动邮箱或短信密码重置流程的输入字段。
 */
public record PasswordResetStartCommand(
        VerificationChannel channel,
        String email,
        String countryIso2,
        String phoneNumber,
        String deviceInstallationId,
        String clientIp) {
}
