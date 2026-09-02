package com.example.temperate.service.auth.oauth.flow;

/**
 * 表示 OAuth 完成请求在 Redis 原子状态机中的裁决结果，防止并发请求重复签发会话。
 */
public enum OAuthCompletionClaim {
    CLAIMED,
    IN_PROGRESS,
    ALREADY_COMPLETED
}
