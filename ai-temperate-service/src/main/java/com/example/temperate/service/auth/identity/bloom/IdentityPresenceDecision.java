package com.example.temperate.service.auth.identity.bloom;

/**
 * 表示已注册身份计数布隆过滤器的三态查询结论。
 */
public enum IdentityPresenceDecision {
    DEFINITELY_ABSENT,
    POSSIBLY_PRESENT,
    UNAVAILABLE
}
