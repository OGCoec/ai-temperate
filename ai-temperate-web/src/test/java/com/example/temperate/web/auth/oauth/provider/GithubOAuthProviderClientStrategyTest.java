package com.example.temperate.web.auth.oauth.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.oauth.domain.OAuthProvider;
import com.example.temperate.service.auth.oauth.domain.TrustedOAuthIdentity;
import com.example.temperate.service.auth.oauth.flow.OAuthAuthorizationStateSnapshot;
import com.example.temperate.service.auth.oauth.flow.OAuthBrowserAuthorization;
import com.example.temperate.service.auth.oauth.flow.OAuthClientPlatform;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 验证 GitHub 浏览器授权固定使用 PKCE、数字 Subject 与主验证邮箱建立可信身份。
 */
class GithubOAuthProviderClientStrategyTest {

    @Test
    void authorizationUrlUsesExactCallbackScopesStateAndPkce() {
        GithubOAuthProviderClientStrategy strategy = strategy(
                exchangeReturning("{\"id\":918273645}", verifiedEmails()),
                successfulTokenClient());

        URI authorizationUri = strategy.authorizationUri(
                new OAuthBrowserAuthorization(
                        "s".repeat(32),
                        "c".repeat(43),
                        null,
                        protectedIdentifier("f")),
                callbackUri());

        assertThat(authorizationUri.getQuery()).contains(
                "scope=read:user user:email",
                "state=" + "s".repeat(32),
                "code_challenge=" + "c".repeat(43),
                "code_challenge_method=S256",
                "redirect_uri=https://niko000o.site/api/auth/oauth2/code/github");
    }

    @Test
    void exchangeUsesNumericGithubIdAndPrimaryVerifiedEmail() {
        GithubOAuthProviderClientStrategy strategy = strategy(
                exchangeReturning("{\"id\":918273645}", verifiedEmails()),
                successfulTokenClient());

        TrustedOAuthIdentity identity = strategy.exchange("provider-code", authorizationState());

        assertThat(identity.provider()).isEqualTo(OAuthProvider.GITHUB);
        assertThat(identity.providerSubject()).isEqualTo("918273645");
        assertThat(identity.verifiedEmail()).isEqualTo("member@example.com");
        assertThat(identity.emailVerified()).isTrue();
    }

    @Test
    void exchangeRejectsMissingStableGithubId() {
        GithubOAuthProviderClientStrategy strategy = strategy(
                exchangeReturning("{\"login\":\"member\"}", verifiedEmails()),
                successfulTokenClient());

        assertThatThrownBy(() -> strategy.exchange("provider-code", authorizationState()))
                .isInstanceOfSatisfying(OAuthProviderException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(OAuthProviderErrorCode.PROVIDER_SUBJECT_MISSING));
    }

    @Test
    void exchangeRejectsAccountsWithoutPrimaryVerifiedEmail() {
        GithubOAuthProviderClientStrategy strategy = strategy(
                exchangeReturning(
                        "{\"id\":918273645}",
                        "[{\"email\":\"member@example.com\","
                                + "\"primary\":true,\"verified\":false}]"),
                successfulTokenClient());

        assertThatThrownBy(() -> strategy.exchange("provider-code", authorizationState()))
                .isInstanceOfSatisfying(OAuthProviderException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(OAuthProviderErrorCode.VERIFIED_EMAIL_MISSING));
    }

    @Test
    void tokenExchangeFailureUsesStableProviderErrorCode() {
        OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenClient =
                mock(OAuth2AccessTokenResponseClient.class);
        when(tokenClient.getTokenResponse(any()))
                .thenThrow(new IllegalStateException("provider secret must not escape"));
        GithubOAuthProviderClientStrategy strategy = strategy(
                exchangeReturning("{\"id\":918273645}", verifiedEmails()), tokenClient);

        assertThatThrownBy(() -> strategy.exchange("provider-code", authorizationState()))
                .isInstanceOfSatisfying(OAuthProviderException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(OAuthProviderErrorCode.TOKEN_EXCHANGE_FAILED));
    }

    private static GithubOAuthProviderClientStrategy strategy(
            ExchangeFunction exchangeFunction,
            OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenClient) {
        WebClient client = WebClient.builder()
                .baseUrl("https://api.github.com")
                .exchangeFunction(exchangeFunction)
                .build();
        return new GithubOAuthProviderClientStrategy(registration(), tokenClient, client);
    }

    private static ExchangeFunction exchangeReturning(String userJson, String emailsJson) {
        return request -> {
            String body = request.url().getPath().equals("/user") ? userJson : emailsJson;
            assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION))
                    .isEqualTo("Bearer provider-access-token");
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .build());
        };
    }

    @SuppressWarnings("unchecked")
    private static OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest>
            successfulTokenClient() {
        OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> client =
                mock(OAuth2AccessTokenResponseClient.class);
        when(client.getTokenResponse(any())).thenReturn(OAuth2AccessTokenResponse
                .withToken("provider-access-token")
                .tokenType(OAuth2AccessToken.TokenType.BEARER)
                .expiresIn(300)
                .build());
        return client;
    }

    private static ClientRegistration registration() {
        return ClientRegistration.withRegistrationId("github")
                .clientId("github-client")
                .clientSecret("github-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(callbackUri().toString())
                .scope("read:user", "user:email")
                .authorizationUri("https://github.com/login/oauth/authorize")
                .tokenUri("https://github.com/login/oauth/access_token")
                .userInfoUri("https://api.github.com/user")
                .userNameAttributeName("id")
                .clientName("GitHub")
                .build();
    }

    private static OAuthAuthorizationStateSnapshot authorizationState() {
        return new OAuthAuthorizationStateSnapshot(
                protectedIdentifier("f"),
                OAuthProvider.GITHUB,
                OAuthClientPlatform.H5,
                "v".repeat(43),
                protectedIdentifier("n"),
                callbackUri().toString());
    }

    private static HmacIdentifier protectedIdentifier(String value) {
        return HmacIdentifier.fromProtectedValue(value.repeat(43));
    }

    private static URI callbackUri() {
        return URI.create("https://niko000o.site/api/auth/oauth2/code/github");
    }

    private static String verifiedEmails() {
        return "[{\"email\":\"secondary@example.com\","
                + "\"primary\":false,\"verified\":true},"
                + "{\"email\":\"member@example.com\","
                + "\"primary\":true,\"verified\":true}]";
    }
}
