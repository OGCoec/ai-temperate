package com.example.temperate.service.registration.dto.command;

/**
 * 承载启动注册流程时提交的邮箱、手机号、设备和客户端网络信息。
 */
public record RegistrationStartCommand(
        String email,
        String countryIso2,
        String nationalPhoneNumber,
        String deviceInstallationId,
        String canonicalIp) {
}
