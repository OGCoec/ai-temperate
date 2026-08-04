package com.example.temperate.service.auth.login.dto.result;

/**
 * 区分登录已完成和仍需 TOTP 二次认证的稳定响应状态。
 */
public enum LoginFlowStatus {
    AUTHENTICATED,
    TOTP_REQUIRED
}
