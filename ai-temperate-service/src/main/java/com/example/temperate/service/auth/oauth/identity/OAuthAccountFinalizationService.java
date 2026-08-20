package com.example.temperate.service.auth.oauth.identity;

import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.service.auth.oauth.domain.TrustedOAuthIdentity;

/**
 * 定义手机号证明完成后重新裁决并原子创建或绑定 OAuth 账号的事务边界。
 */
public interface OAuthAccountFinalizationService {

    AuthenticationContext finalizeIdentity(
            TrustedOAuthIdentity identity,
            String verifiedPhone);
}
