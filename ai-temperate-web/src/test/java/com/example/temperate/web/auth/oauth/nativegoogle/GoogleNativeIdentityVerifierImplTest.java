package com.example.temperate.web.auth.oauth.nativegoogle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.web.auth.oauth.config.OAuthClientProperties;
import com.example.temperate.web.auth.oauth.nativegoogle.impl.GoogleNativeIdentityVerifierImpl;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * 验证 Android Google ID Token 只在 issuer、audience、nonce 和已验证邮箱完整时转换为可信身份。
 */
class GoogleNativeIdentityVerifierImplTest {

    private static final String VALID_NONCE = "n".repeat(43);

    @Test
    void shouldReturnTrustedIdentityAndNonce() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        when(decoder.decode("signed-id-token")).thenReturn(jwt(true));
        GoogleNativeIdentityVerifier verifier = new GoogleNativeIdentityVerifierImpl(
                decoder, properties());

        VerifiedGoogleNativeIdentity result = verifier.verify("signed-id-token");

        assertEquals("google-subject", result.identity().providerSubject());
        assertEquals(VALID_NONCE, result.rawNonce());
    }

    @Test
    void shouldRejectUnverifiedEmail() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        when(decoder.decode("signed-id-token")).thenReturn(jwt(false));
        GoogleNativeIdentityVerifier verifier = new GoogleNativeIdentityVerifierImpl(
                decoder, properties());

        assertThrows(RuntimeException.class, () -> verifier.verify("signed-id-token"));
    }

    private static Jwt jwt(boolean emailVerified) {
        Instant now = Instant.parse("2026-08-19T00:00:00Z");
        return Jwt.withTokenValue("signed-id-token")
                .header("alg", "RS256")
                .issuer("https://accounts.google.com")
                .subject("google-subject")
                .audience(List.of("google-server-client"))
                .issuedAt(now.minusSeconds(5))
                .expiresAt(now.plusSeconds(300))
                .claim("nonce", VALID_NONCE)
                .claim("email", "member@example.com")
                .claim("email_verified", emailVerified)
                .build();
    }

    private static OAuthClientProperties properties() {
        return new OAuthClientProperties(
                true,
                URI.create("https://niko000o.site"),
                URI.create("https://niko000o.site/pages/auth/oauth-return"),
                URI.create("https://niko000o.site/app/oauth-return"),
                new OAuthClientProperties.Google(
                        "google-web-client", "google-secret", "google-server-client"),
                new OAuthClientProperties.Github("github-client", "github-secret"));
    }
}
