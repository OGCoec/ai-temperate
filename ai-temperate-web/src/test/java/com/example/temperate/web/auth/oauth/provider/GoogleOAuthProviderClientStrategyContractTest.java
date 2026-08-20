package com.example.temperate.web.auth.oauth.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.oauth.flow.OAuthBrowserAuthorization;
import com.example.temperate.web.auth.oauth.config.OAuthClientProperties;
import java.net.URI;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import static org.mockito.Mockito.mock;
import org.junit.jupiter.api.Test;

/**
 * 验证 H5 Google 授权请求固定启用账号选择、PKCE S256、OIDC nonce 和最小 Scope。
 */
class GoogleOAuthProviderClientStrategyContractTest {

    @Test
    void authorizationUrlAlwaysRequestsExplicitAccountSelection() {
        OAuthClientProperties properties = properties();
        GoogleOAuthProviderClientStrategy strategy = new GoogleOAuthProviderClientStrategy(
                googleRegistration(properties),
                mock(OAuth2AccessTokenResponseClient.class),
                mock(JwtDecoder.class),
                mock(AuthSessionSecretProtector.class));
        OAuthBrowserAuthorization authorization = new OAuthBrowserAuthorization(
                "s".repeat(32),
                "c".repeat(43),
                "n".repeat(43),
                HmacIdentifier.fromProtectedValue("f".repeat(43)));

        URI uri = strategy.authorizationUri(
                authorization,
                properties.callbackUri(com.example.temperate.service.auth.oauth.domain.OAuthProvider.GOOGLE));
        String query = uri.getRawQuery();

        assertThat(query).contains("prompt=select_account");
        assertThat(query).contains("code_challenge_method=S256");
        assertThat(query).contains("nonce=");
        assertThat(query).contains("scope=openid%20profile%20email");
    }

    private static ClientRegistration googleRegistration(OAuthClientProperties properties) {
        return ClientRegistration.withRegistrationId("google")
                .clientId(properties.google().clientId())
                .clientSecret(properties.google().clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(properties.callbackUri(
                        com.example.temperate.service.auth.oauth.domain.OAuthProvider.GOOGLE)
                        .toString())
                .scope("openid", "profile", "email")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                .userNameAttributeName("sub")
                .clientName("Google")
                .build();
    }

    private static OAuthClientProperties properties() {
        return new OAuthClientProperties(
                true,
                URI.create("https://niko000o.site"),
                URI.create("https://niko000o.site/pages/auth/oauth-return"),
                URI.create("https://niko000o.site/app/oauth-return"),
                new OAuthClientProperties.Google(
                        "google-web-client", "google-secret-value", "google-server-client"),
                new OAuthClientProperties.Github(
                        "github-client", "github-secret-value"));
    }
}
