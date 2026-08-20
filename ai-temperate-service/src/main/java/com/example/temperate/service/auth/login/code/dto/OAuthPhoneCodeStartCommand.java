package com.example.temperate.service.auth.login.code.dto;

/**
 * 表示 OAuth Flow 已要求补手机号后，由服务端创建专用手机验证码子流程的命令。
 */
public record OAuthPhoneCodeStartCommand(
        String countryIso2,
        String phoneNumber,
        String deviceInstallationId,
        String clientIp) {
}
