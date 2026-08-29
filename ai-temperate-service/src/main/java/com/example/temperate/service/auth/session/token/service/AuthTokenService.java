package com.example.temperate.service.auth.session.token.service;

import com.example.temperate.service.auth.session.token.dto.result.VerifiedAccessToken;
import java.time.Duration;

/**
 * 定义访问令牌签发与验证，以及 Refresh、流程和 CSRF 随机凭据生成的令牌能力边界。
 */
public interface AuthTokenService {

    String issueAccessToken(long userId);

    /**
     * 为具有独立安全边界的内部流程签发显式有效期令牌；普通登录不得绕过全局短期 TTL 使用该重载。
     */
    String issueAccessToken(long userId, Duration timeToLive);

    VerifiedAccessToken verifyAccessToken(String rawAccessToken);

    String newRefreshToken();

    String newFlowToken();

    String newCsrfToken();
}
