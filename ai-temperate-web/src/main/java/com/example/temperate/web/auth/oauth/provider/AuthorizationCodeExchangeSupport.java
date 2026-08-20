package com.example.temperate.web.auth.oauth.provider;

import com.example.temperate.service.auth.oauth.flow.OAuthAuthorizationStateSnapshot;
import java.util.Map;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;

/**
 * 使用 Spring Security OAuth2 Client 组装 Authorization Code 与 PKCE verifier 的一次性换码请求。
 */
final class AuthorizationCodeExchangeSupport {

    private AuthorizationCodeExchangeSupport() {
    }

    static OAuth2AccessTokenResponse exchange(
            ClientRegistration registration,
            OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenClient,
            String code,
            OAuthAuthorizationStateSnapshot state) {
        if (code == null || code.isBlank() || code.length() > 2048) {
            throw new OAuthProviderException(
                    OAuthProviderErrorCode.AUTHORIZATION_REJECTED,
                    "OAuth authorization code is invalid.");
        }
        OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
                .clientId(registration.getClientId())
                .redirectUri(state.redirectUri())
                .scopes(registration.getScopes())
                .state("consumed")
                .attributes(Map.of(PkceParameterNames.CODE_VERIFIER, state.codeVerifier()))
                .build();
        OAuth2AuthorizationResponse response = OAuth2AuthorizationResponse.success(code)
                .redirectUri(state.redirectUri())
                .state("consumed")
                .build();
        try {
            return tokenClient.getTokenResponse(new OAuth2AuthorizationCodeGrantRequest(
                    registration,
                    new OAuth2AuthorizationExchange(request, response)));
        } catch (RuntimeException exception) {
            throw new OAuthProviderException(
                    OAuthProviderErrorCode.TOKEN_EXCHANGE_FAILED,
                    "OAuth authorization code exchange failed.", exception);
        }
    }
}
