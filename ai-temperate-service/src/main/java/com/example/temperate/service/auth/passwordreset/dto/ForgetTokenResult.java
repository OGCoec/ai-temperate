package com.example.temperate.service.auth.passwordreset.dto;

import java.time.Instant;

/**
 * 返回验证码校验成功后可用于完成密码重置的一次性找回凭据及到期时间。
 */
public record ForgetTokenResult(String forgetToken, Instant expiresAt) {
}
