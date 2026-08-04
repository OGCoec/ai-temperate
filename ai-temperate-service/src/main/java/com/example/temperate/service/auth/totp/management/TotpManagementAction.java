package com.example.temperate.service.auth.totp.management;

/**
 * 区分 TOTP 开启、密钥轮换和关闭三类不可混用的敏感操作。
 */
public enum TotpManagementAction {
    ENABLE,
    ROTATE,
    DISABLE
}
