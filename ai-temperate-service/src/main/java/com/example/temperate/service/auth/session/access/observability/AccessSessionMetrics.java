package com.example.temperate.service.auth.session.access.observability;

/**
 * 定义普通用户 RT-first 会话认证的低基数观测事件，不接收任何用户或凭据标签。
 */
public interface AccessSessionMetrics {

    void accessValid();

    void accessRenewed();

    void refreshInvalid();

    void accessInvalid();

    void sessionMismatch();

    void ttlInvariantViolation();

    void infrastructureFailure();
}
