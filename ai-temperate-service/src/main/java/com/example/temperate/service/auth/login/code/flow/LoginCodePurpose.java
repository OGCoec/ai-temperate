package com.example.temperate.service.auth.login.code.flow;

/**
 * 表示手机验证码状态机由公开登录还是受控 OAuth 补手机号流程创建。
 */
public enum LoginCodePurpose {
    LOGIN,
    OAUTH_PHONE
}
