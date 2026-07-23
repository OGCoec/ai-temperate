package com.example.temperate.service.auth.session.token.service;

import com.example.temperate.service.auth.session.token.dto.result.VerifiedAccessToken;

/**
 * 定义访问令牌验证及刷新、流程、CSRF 随机凭据生成的认证令牌服务边界。
 */
public interface AuthTokenService {

    String issueAccessToken(long userId);

    VerifiedAccessToken verifyAccessToken(String rawAccessToken);

    String newRefreshToken();

    String newFlowToken();

    String newCsrfToken();
}
