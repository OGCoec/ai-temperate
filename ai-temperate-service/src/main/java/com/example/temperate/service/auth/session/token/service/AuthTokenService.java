package com.example.temperate.service.auth.session.token.service;

import com.example.temperate.service.auth.session.token.dto.result.VerifiedAccessToken;

/**
 * 定义访问令牌签发与验证，以及 Refresh、流程和 CSRF 随机凭据生成的令牌能力边界。
 */
public interface AuthTokenService {

    String issueAccessToken(long userId);

    VerifiedAccessToken verifyAccessToken(String rawAccessToken);

    String newRefreshToken();

    String newFlowToken();

    String newCsrfToken();
}
