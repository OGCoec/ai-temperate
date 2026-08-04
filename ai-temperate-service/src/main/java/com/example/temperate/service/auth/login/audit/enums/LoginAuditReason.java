package com.example.temperate.service.auth.login.audit.enums;

/**
 * 枚举登录审计和指标中使用的结果原因分类。
 */
public enum LoginAuditReason {
    AUTHENTICATED,
    PRIMARY_FACTOR_VERIFIED,
    INVALID_CREDENTIALS,
    BLOCKED,
    ACCOUNT_STATUS,
    PASSWORD_RESET_REQUIRED,
    INFRASTRUCTURE,
    PASSWORD_UPGRADED,
    PASSWORD_UPGRADE_CONFLICT
}
