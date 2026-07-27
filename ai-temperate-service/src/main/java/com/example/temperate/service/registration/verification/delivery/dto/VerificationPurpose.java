package com.example.temperate.service.registration.verification.delivery.dto;

/**
 * 验证码投递对应的业务用途枚举。
 *
 * <p>用途：让邮件和短信模板在不依赖调用方字符串的前提下区分普通注册、管理员初始化、登录与密码重置场景。</p>
 */
public enum VerificationPurpose {
    REGISTRATION,
    ADMIN_REGISTRATION,
    LOGIN,
    PASSWORD_RESET
}
