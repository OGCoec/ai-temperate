package com.example.temperate.service.risk.domain;

/**
 * 表示 PreAuth 当前关联的认证会话类型，Redis 中只保存关联摘要而不保存原始会话 Token。
 */
public enum RiskSessionType {
    NONE,
    USER_REFRESH,
    ADMIN_SESSION
}
