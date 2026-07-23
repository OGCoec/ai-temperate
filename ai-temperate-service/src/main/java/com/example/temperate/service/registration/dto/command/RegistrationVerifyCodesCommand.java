package com.example.temperate.service.registration.dto.command;

import com.example.temperate.service.registration.flow.security.RegistrationAccess;

/**
 * 承载注册流程同时校验邮箱和短信验证码的访问参数。
 */
public record RegistrationVerifyCodesCommand(
        RegistrationAccess access,
        String emailCode,
        String smsCode) {
}
