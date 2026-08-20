package com.example.temperate.web.auth.oauth.nativegoogle.impl;

import com.example.temperate.service.auth.oauth.domain.OAuthProofType;
import com.example.temperate.service.auth.oauth.domain.OAuthProvider;
import com.example.temperate.service.auth.oauth.domain.TrustedOAuthIdentity;
import com.example.temperate.web.auth.oauth.config.OAuthClientProperties;
import com.example.temperate.web.auth.oauth.nativegoogle.GoogleNativeIdentityVerifier;
import com.example.temperate.web.auth.oauth.nativegoogle.VerifiedGoogleNativeIdentity;
import com.example.temperate.web.auth.oauth.provider.OAuthProviderErrorCode;
import com.example.temperate.web.auth.oauth.provider.OAuthProviderException;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Service;

/**
 * 验证 Android Credential Manager 返回的 Google JWS，并提取 nonce、稳定 Subject 与已验证邮箱。
 *
 * <p>ID Token 只作为当前方法参数与局部变量存在；该服务不写日志、Storage、Redis 或数据库，nonce 的一次性
 * 消费由调用方在签名验证成功后立即交给 OAuth Flow Lua 完成。</p>
 */
@Service
@ConditionalOnProperty(prefix = "app.oauth", name = "enabled", havingValue = "true")
public final class GoogleNativeIdentityVerifierImpl implements GoogleNativeIdentityVerifier {

    private final JwtDecoder jwtDecoder;
    private final OAuthClientProperties properties;

    public GoogleNativeIdentityVerifierImpl(
            @Qualifier("googleNativeJwtDecoder") JwtDecoder jwtDecoder,
            OAuthClientProperties properties) {
        this.jwtDecoder = Objects.requireNonNull(jwtDecoder);
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    public VerifiedGoogleNativeIdentity verify(String rawIdToken) {
        if (rawIdToken == null || rawIdToken.isBlank() || rawIdToken.length() > 16_384) {
            throw unverified();
        }
        final Jwt jwt;
        try {
            jwt = jwtDecoder.decode(rawIdToken);
        } catch (RuntimeException exception) {
            throw new OAuthProviderException(
                    OAuthProviderErrorCode.IDENTITY_UNVERIFIED,
                    "Google native identity token is invalid.", exception);
        }
        String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
        List<String> audience = jwt.getAudience();
        String nonce = jwt.getClaimAsString("nonce");
        String email = jwt.getClaimAsString("email");
        Boolean emailVerified = jwt.getClaimAsBoolean("email_verified");
        if (!("https://accounts.google.com".equals(issuer)
                || "accounts.google.com".equals(issuer))
                || audience == null
                || !audience.contains(properties.google().androidServerClientId())
                || nonce == null
                || !nonce.matches("^[A-Za-z0-9_-]{43}$")
                || !Boolean.TRUE.equals(emailVerified)) {
            throw unverified();
        }
        try {
            TrustedOAuthIdentity identity = new TrustedOAuthIdentity(
                    OAuthProvider.GOOGLE,
                    jwt.getSubject(),
                    email,
                    true,
                    OAuthProofType.GOOGLE_NATIVE_ID_TOKEN);
            return new VerifiedGoogleNativeIdentity(identity, nonce);
        } catch (IllegalArgumentException exception) {
            throw unverified();
        }
    }

    private static OAuthProviderException unverified() {
        return new OAuthProviderException(
                OAuthProviderErrorCode.IDENTITY_UNVERIFIED,
                "Google native identity is unverified.");
    }
}
