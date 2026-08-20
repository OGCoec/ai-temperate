package com.example.temperate.web.auth.oauth.provider;

import com.example.temperate.service.auth.oauth.domain.OAuthProofType;
import com.example.temperate.service.auth.oauth.domain.OAuthProvider;
import com.example.temperate.service.auth.oauth.domain.TrustedOAuthIdentity;
import com.example.temperate.service.auth.oauth.flow.OAuthAuthorizationStateSnapshot;
import com.example.temperate.service.auth.oauth.flow.OAuthBrowserAuthorization;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 使用 GitHub 数字用户 ID 作为稳定 Subject，并只接受 emails API 中主且已验证的邮箱。
 */
@Component("githubOAuthProviderClientStrategy")
@ConditionalOnProperty(prefix = "app.oauth", name = "enabled", havingValue = "true")
public final class GithubOAuthProviderClientStrategy implements OAuthProviderClientStrategy {

    // GitHub身份接口可能因跨境网络抖动超过默认短超时；30秒只设定最长等待，成功响应仍会立即继续。
    private static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(30);

    private final ClientRegistration registration;
    private final OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenClient;
    private final WebClient githubClient;

    public GithubOAuthProviderClientStrategy(
            @Qualifier("githubOAuthClientRegistration") ClientRegistration registration,
            OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenClient,
            @Qualifier("githubOAuthWebClient") WebClient githubClient) {
        this.registration = Objects.requireNonNull(registration);
        this.tokenClient = Objects.requireNonNull(tokenClient);
        this.githubClient = Objects.requireNonNull(githubClient);
    }

    @Override
    public OAuthProvider provider() {
        return OAuthProvider.GITHUB;
    }

    @Override
    public URI authorizationUri(OAuthBrowserAuthorization authorization, URI redirectUri) {
        return UriComponentsBuilder
                .fromUriString(registration.getProviderDetails().getAuthorizationUri())
                .queryParam("client_id", registration.getClientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", "read:user user:email")
                .queryParam("state", authorization.rawState())
                .queryParam("code_challenge", authorization.codeChallenge())
                .queryParam("code_challenge_method", "S256")
                .build()
                .encode()
                .toUri();
    }

    @Override
    public TrustedOAuthIdentity exchange(
            String authorizationCode,
            OAuthAuthorizationStateSnapshot state) {
        OAuth2AccessTokenResponse tokenResponse = AuthorizationCodeExchangeSupport.exchange(
                registration, tokenClient, authorizationCode, state);
        String accessToken = tokenResponse.getAccessToken().getTokenValue();
        try {
            Map<String, Object> user = githubClient.get()
                    .uri("/user")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() { })
                    .block(PROVIDER_TIMEOUT);
            List<Map<String, Object>> emails = githubClient.get()
                    .uri("/user/emails")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() { })
                    .block(PROVIDER_TIMEOUT);
            String subject = stableSubject(user);
            String verifiedEmail = primaryVerifiedEmail(emails);
            return trustedIdentity(subject, verifiedEmail);
        } catch (OAuthProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new OAuthProviderException(
                    OAuthProviderErrorCode.PROVIDER_UNAVAILABLE,
                    "GitHub identity lookup failed.", exception);
        }
    }

    private static String stableSubject(Map<String, Object> user) {
        Object id = user == null ? null : user.get("id");
        String value = id instanceof Number number
                ? Long.toString(number.longValue())
                : Objects.toString(id, "");
        if (!value.matches("^[1-9][0-9]{0,19}$")) {
            throw subjectMissing();
        }
        return value;
    }

    private static String primaryVerifiedEmail(List<Map<String, Object>> emails) {
        if (emails == null) {
            throw verifiedEmailMissing();
        }
        return emails.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("primary")))
                .filter(item -> Boolean.TRUE.equals(item.get("verified")))
                .map(item -> Objects.toString(item.get("email"), ""))
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElseThrow(GithubOAuthProviderClientStrategy::verifiedEmailMissing);
    }

    private static TrustedOAuthIdentity trustedIdentity(
            String subject, String verifiedEmail) {
        try {
            return new TrustedOAuthIdentity(
                    OAuthProvider.GITHUB,
                    subject,
                    verifiedEmail,
                    true,
                    OAuthProofType.BROWSER_AUTHORIZATION_CODE);
        } catch (IllegalArgumentException exception) {
            throw verifiedEmailMissing();
        }
    }

    private static OAuthProviderException subjectMissing() {
        return new OAuthProviderException(
                OAuthProviderErrorCode.PROVIDER_SUBJECT_MISSING,
                "GitHub stable subject is missing.");
    }

    private static OAuthProviderException verifiedEmailMissing() {
        return new OAuthProviderException(
                OAuthProviderErrorCode.VERIFIED_EMAIL_MISSING,
                "GitHub primary verified email is missing.");
    }
}
