package com.example.temperate.service.admin.login;

/**
 * 承载管理员登录时三项身份、密码和一次性 hCaptcha 响应。
 */
public record AdminLoginCompleteCommand(
        AdminLoginAccess access,
        String email,
        String countryIso2,
        String phoneNumber,
        String password,
        String hcaptchaToken) {

    @Override
    public String toString() {
        return "AdminLoginCompleteCommand[redacted]";
    }
}
