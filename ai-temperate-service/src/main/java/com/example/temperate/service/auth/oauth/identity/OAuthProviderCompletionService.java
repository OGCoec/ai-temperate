package com.example.temperate.service.auth.oauth.identity;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.oauth.domain.TrustedOAuthIdentity;

/**
 * 定义 Provider 证明验证成功后解析本地账号并推进 OAuth Flow 的业务入口。
 */
public interface OAuthProviderCompletionService {

    OAuthAccountDecision accept(
            HmacIdentifier flowId,
            TrustedOAuthIdentity identity);
}
