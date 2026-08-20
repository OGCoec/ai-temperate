package com.example.temperate.web.auth.oauth.provider;

import com.example.temperate.service.auth.oauth.domain.OAuthProvider;
import com.example.temperate.service.auth.oauth.domain.TrustedOAuthIdentity;
import com.example.temperate.service.auth.oauth.flow.OAuthAuthorizationStateSnapshot;
import com.example.temperate.service.auth.oauth.flow.OAuthBrowserAuthorization;
import java.net.URI;

/**
 * 定义浏览器 Provider 授权 URL 构造以及 Authorization Code 换取可信身份的统一策略。
 */
public interface OAuthProviderClientStrategy {

    OAuthProvider provider();

    URI authorizationUri(OAuthBrowserAuthorization authorization, URI redirectUri);

    TrustedOAuthIdentity exchange(
            String authorizationCode,
            OAuthAuthorizationStateSnapshot state);
}
