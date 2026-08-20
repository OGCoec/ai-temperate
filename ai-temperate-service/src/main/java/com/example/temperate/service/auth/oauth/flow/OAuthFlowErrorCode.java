package com.example.temperate.service.auth.oauth.flow;

/**
 * 表示 OAuth 短时状态机对外归一化的访问、重放、过期和基础设施错误。
 */
public enum OAuthFlowErrorCode {
    FLOW_NOT_FOUND,
    FLOW_EXPIRED,
    FLOW_FORBIDDEN,
    STATE_REJECTED,
    NONCE_REJECTED,
    INVALID_TRANSITION,
    INFRASTRUCTURE_UNAVAILABLE
}
