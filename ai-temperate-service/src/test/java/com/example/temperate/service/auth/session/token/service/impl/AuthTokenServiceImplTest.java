package com.example.temperate.service.auth.session.token.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.common.jwt.component.JwtUtils;
import com.example.temperate.service.auth.session.token.dto.result.VerifiedAccessToken;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证访问令牌声明、版本校验、到期判断与随机凭据生成规则。
 */
class AuthTokenServiceImplTest {

    private static final byte[] TEST_KEY_BYTES =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final String TOKEN_ID = "B2345678901234567890123456789012345678";

    private SecretKey signingKey;
    private JwtUtils jwtUtils;
    private PublicIdCodec publicIdCodec;
    private AuthTokenServiceImpl service;

    @BeforeEach
    void setUp() {
        signingKey = Keys.hmacShaKeyFor(TEST_KEY_BYTES);
        jwtUtils = new JwtUtils(signingKey);
        publicIdCodec = new PublicIdCodec();
        service = new AuthTokenServiceImpl(jwtUtils, publicIdCodec);
    }

    @Test
    void issuesTenMinuteAccessTokenWithOnlyV2PublicClaims() {
        String token = service.issueAccessToken(10001L);

        Claims claims = jwtUtils.parseToken(token);
        assertThat(claims.getSubject()).isEqualTo(publicIdCodec.encode(10001L));
        assertThat(claims.get("jti", String.class)).matches("^[A-Za-z0-9_-]{38}$");
        assertThat(((Number) claims.get("ver")).intValue()).isEqualTo(2);
        assertThat(claims.getExpiration().toInstant().getEpochSecond()
                - claims.getIssuedAt().toInstant().getEpochSecond()).isEqualTo(600L);
        assertThat(claims.keySet()).doesNotContainAnyElementsOf(Set.of(
                "userId", "sid", "pwdVersion", "email", "phone", "membershipTier"));
    }

    @Test
    void usesConfiguredAccessTokenTtl() {
        AuthTokenServiceImpl configuredService =
                new AuthTokenServiceImpl(
                        jwtUtils,
                        publicIdCodec,
                        new SecureRandom(),
                        Duration.ofMinutes(15));

        Claims claims = jwtUtils.parseToken(configuredService.issueAccessToken(10001L));

        assertThat(claims.getExpiration().toInstant().getEpochSecond()
                - claims.getIssuedAt().toInstant().getEpochSecond()).isEqualTo(900L);
    }

    @Test
    void issuesAnExplicitlyBoundAccessTokenWithoutChangingTheDefaultTtl() {
        Claims longLived = jwtUtils.parseToken(
                service.issueAccessToken(10001L, Duration.ofHours(15)));
        Claims normal = jwtUtils.parseToken(service.issueAccessToken(10001L));

        assertThat(longLived.getExpiration().toInstant().getEpochSecond()
                - longLived.getIssuedAt().toInstant().getEpochSecond()).isEqualTo(54_000L);
        assertThat(normal.getExpiration().toInstant().getEpochSecond()
                - normal.getIssuedAt().toInstant().getEpochSecond()).isEqualTo(600L);
    }

    @Test
    void verifiesActiveAndExpiredTokensWithoutSkippingSignatureVerification() {
        String active = service.issueAccessToken(10001L);

        VerifiedAccessToken activeClaims = service.verifyAccessToken(active);
        assertThat(activeClaims.publicId()).isEqualTo(publicIdCodec.encode(10001L));
        assertThat(activeClaims.schemaVersion()).isEqualTo(2);
        assertThat(activeClaims.tokenId()).matches("^[A-Za-z0-9_-]{38}$");
        assertThat(activeClaims.expired()).isFalse();

        String expired = signedToken(
                publicIdCodec.encode(10001L),
                TOKEN_ID,
                2,
                Instant.now().minusSeconds(120),
                Instant.now().minusSeconds(60));
        assertThat(service.verifyAccessToken(expired).expired()).isTrue();

        int signatureStart = expired.lastIndexOf('.') + 1;
        char replacement = expired.charAt(signatureStart) == 'A' ? 'B' : 'A';
        String tampered = expired.substring(0, signatureStart)
                + replacement
                + expired.substring(signatureStart + 1);
        assertThatExceptionOfType(JwtException.class)
                .isThrownBy(() -> service.verifyAccessToken(tampered));
    }

    @Test
    void rejectsNonCanonicalSubjectAndUnsupportedSchema() {
        String rawLongSubject = signedToken(
                "10001", TOKEN_ID, 2, Instant.now(), Instant.now().plusSeconds(600));
        String oldSchema = signedToken(
                publicIdCodec.encode(10001L),
                TOKEN_ID,
                1,
                Instant.now(),
                Instant.now().plusSeconds(600));

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> service.verifyAccessToken(rawLongSubject));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> service.verifyAccessToken(oldSchema));
    }

    @Test
    void generatesNanoIdRefreshAndFlowTokensAndThirtyTwoByteCsrfTokens() {
        String refreshToken = service.newRefreshToken();
        String flowToken = service.newFlowToken();
        String csrfToken = service.newCsrfToken();

        assertThat(refreshToken).matches("^[A-Za-z0-9_-]{38}$");
        assertThat(flowToken).matches("^[A-Za-z0-9_-]{38}$");
        assertThat(csrfToken).matches("^[A-Za-z0-9_-]{43}$");
        assertThat(java.util.Base64.getUrlDecoder().decode(csrfToken)).hasSize(32);
        assertThat(service.newRefreshToken()).isNotEqualTo(refreshToken);
        assertThat(service.newFlowToken()).isNotEqualTo(flowToken);
        assertThat(service.newCsrfToken()).isNotEqualTo(csrfToken);
    }

    private String signedToken(
            String subject,
            String tokenId,
            int schemaVersion,
            Instant issuedAt,
            Instant expiresAt) {
        return Jwts.builder()
                .subject(subject)
                .claim("jti", tokenId)
                .claim("ver", schemaVersion)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }
}
