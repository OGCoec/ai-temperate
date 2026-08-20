package com.example.temperate.web.auth.oauth.provider;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.oauth.domain.OAuthProofType;
import com.example.temperate.service.auth.oauth.domain.OAuthProvider;
import com.example.temperate.service.auth.oauth.domain.TrustedOAuthIdentity;
import com.example.temperate.service.auth.oauth.flow.OAuthAuthorizationStateSnapshot;
import com.example.temperate.service.auth.oauth.flow.OAuthBrowserAuthorization;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 构造强制账号选择的 Google OIDC 授权请求，并验证签名 ID Token、nonce、Subject 与已验证邮箱。
 */
@Component("googleOAuthProviderClientStrategy")
@ConditionalOnProperty(prefix = "app.oauth", name = "enabled", havingValue = "true")
public final class GoogleOAuthProviderClientStrategy implements OAuthProviderClientStrategy {

    private final ClientRegistration registration;
    private final OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenClient;
    private final JwtDecoder jwtDecoder;
    private final AuthSessionSecretProtector protector;

    public GoogleOAuthProviderClientStrategy(
            @Qualifier("googleOAuthClientRegistration") ClientRegistration registration,
            OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenClient,
            @Qualifier("googleBrowserJwtDecoder") JwtDecoder jwtDecoder,
            AuthSessionSecretProtector protector) {
        this.registration = Objects.requireNonNull(registration);
        this.tokenClient = Objects.requireNonNull(tokenClient);
        this.jwtDecoder = Objects.requireNonNull(jwtDecoder);
        this.protector = Objects.requireNonNull(protector);
    }

    @Override
    public OAuthProvider provider() {
        return OAuthProvider.GOOGLE;
    }

    @Override
    public URI authorizationUri(OAuthBrowserAuthorization authorization, URI redirectUri) {
        return UriComponentsBuilder
                .fromUriString(registration.getProviderDetails().getAuthorizationUri())
                .queryParam("client_id", registration.getClientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", "openid profile email")
                .queryParam("state", authorization.rawState())
                .queryParam("code_challenge", authorization.codeChallenge())
                .queryParam("code_challenge_method", "S256")
                .queryParam("nonce", authorization.rawNonce())
                .queryParam("prompt", "select_account")
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
        Object rawIdToken = tokenResponse.getAdditionalParameters().get("id_token");
        if (!(rawIdToken instanceof String idToken) || idToken.isBlank()) {
            throw unverified();
        }
        final Jwt jwt;
        try {
            jwt = jwtDecoder.decode(idToken);
        } catch (RuntimeException exception) {
            throw new OAuthProviderException(
                    OAuthProviderErrorCode.IDENTITY_UNVERIFIED,
                    "Google identity token is invalid.", exception);
        }
        requireAudienceAndIssuer(jwt);
        String nonce = jwt.getClaimAsString("nonce");
        HmacIdentifier expectedNonce = state.nonceId();
        if (expectedNonce == null
                || nonce == null
                || !matchesNonce(expectedNonce, nonce)) {
            throw unverified();
        }
        String subject = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        Boolean emailVerified = jwt.getClaimAsBoolean("email_verified");
        if (!Boolean.TRUE.equals(emailVerified)) {
            throw unverified();
        }
        try {
            return new TrustedOAuthIdentity(
                    OAuthProvider.GOOGLE,
                    subject,
                    email,
                    true,
                    OAuthProofType.BROWSER_AUTHORIZATION_CODE);
        } catch (IllegalArgumentException exception) {
            throw unverified();
        }
    }

    private boolean matchesNonce(HmacIdentifier expectedNonce, String rawNonce) {
        try {
            return expectedNonce.equals(protector.oauthNonce(rawNonce));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void requireAudienceAndIssuer(Jwt jwt) {
        String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
        List<String> audience = jwt.getAudience();
        if (!("https://accounts.google.com".equals(issuer)
                || "accounts.google.com".equals(issuer))
                || audience == null
                || !audience.contains(registration.getClientId())) {
            throw unverified();
        }
    }

    private static OAuthProviderException unverified() {
        return new OAuthProviderException(
                OAuthProviderErrorCode.IDENTITY_UNVERIFIED,
                "Google identity is unverified.");
    }
}
