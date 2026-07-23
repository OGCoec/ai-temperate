package com.example.temperate.common.jwt.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * 验证 JWT 签发、签名校验、过期处理和密钥边界约束。
 */
class JwtUtilsTest {

    private static final byte[] TEST_KEY_BYTES =
            "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test
    void usesAnInjectedKeyAndProvidesSynchronousTokenRoundTrip() throws Exception {
        SecretKey key = Keys.hmacShaKeyFor(TEST_KEY_BYTES);
        Constructor<JwtUtils> constructor = JwtUtils.class.getConstructor(SecretKey.class);
        JwtUtils codec = constructor.newInstance(key);

        Method generate = JwtUtils.class.getMethod("generateToken", String.class, Duration.class);
        Method parse = JwtUtils.class.getMethod("parseToken", String.class);

        assertThat(generate.getReturnType()).isEqualTo(String.class);
        assertThat(parse.getReturnType()).isEqualTo(Claims.class);

        String token = (String) generate.invoke(codec, "user-42", Duration.ofMinutes(5));
        Claims claims = (Claims) parse.invoke(codec, token);

        assertThat(claims.getSubject()).isEqualTo("user-42");
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
    }

    @Test
    void rejectsMissingMalformedAndWeakBase64Secrets() throws Exception {
        Method factory = JwtUtils.class.getMethod("fromBase64", String.class);

        assertFactoryRejects(factory, " ");
        assertFactoryRejects(factory, "not-base64!");
        assertFactoryRejects(
                factory,
                Base64.getEncoder().encodeToString("too-short".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsWeakOrNonHmacInjectedKeysImmediately() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new JwtUtils(new SecretKeySpec(new byte[16], "HmacSHA256")));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new JwtUtils(new SecretKeySpec(new byte[32], "AES")));
    }

    @Test
    void rejectsSignedTokenWithoutExpiration() {
        SecretKey key = Keys.hmacShaKeyFor(TEST_KEY_BYTES);
        JwtUtils codec = new JwtUtils(key);
        String token = Jwts.builder()
                .subject("user-42")
                .issuedAt(new Date())
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatExceptionOfType(JwtException.class)
                .isThrownBy(() -> codec.parseToken(token));
    }

    @Test
    void rejectsExpiredToken() {
        SecretKey key = Keys.hmacShaKeyFor(TEST_KEY_BYTES);
        JwtUtils codec = new JwtUtils(key);
        String token = Jwts.builder()
                .subject("user-42")
                .issuedAt(Date.from(Instant.now().minusSeconds(120)))
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatExceptionOfType(JwtException.class)
                .isThrownBy(() -> codec.parseToken(token));
    }

    @Test
    void safelyReturnsClaimsFromExpiredTokenOnlyAfterSignatureVerification() throws Exception {
        SecretKey key = Keys.hmacShaKeyFor(TEST_KEY_BYTES);
        JwtUtils codec = new JwtUtils(key);
        String token = Jwts.builder()
                .subject("AAAAAAAAAAE")
                .claim("sid", "session-123")
                .issuedAt(Date.from(Instant.now().minusSeconds(120)))
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        Method parser = JwtUtils.class.getMethod("parseTokenAllowExpired", String.class);
        Claims claims = (Claims) parser.invoke(codec, token);

        assertThat(claims.getSubject()).isEqualTo("AAAAAAAAAAE");
        assertThat(claims.get("sid", String.class)).isEqualTo("session-123");
    }

    @Test
    void expiredClaimsParserStillRejectsTamperingAndMissingExpiration() throws Exception {
        SecretKey key = Keys.hmacShaKeyFor(TEST_KEY_BYTES);
        JwtUtils codec = new JwtUtils(key);
        Method parser = JwtUtils.class.getMethod("parseTokenAllowExpired", String.class);
        String expired = Jwts.builder()
                .subject("AAAAAAAAAAE")
                .issuedAt(Date.from(Instant.now().minusSeconds(120)))
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        int signatureStart = expired.lastIndexOf('.') + 1;
        char replacement = expired.charAt(signatureStart) == 'A' ? 'B' : 'A';
        String tampered = expired.substring(0, signatureStart)
                + replacement
                + expired.substring(signatureStart + 1);
        String missingExpiration = Jwts.builder()
                .subject("AAAAAAAAAAE")
                .issuedAt(new Date())
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> parser.invoke(codec, tampered))
                .withCauseInstanceOf(JwtException.class);
        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> parser.invoke(codec, missingExpiration))
                .withCauseInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTamperedToken() {
        JwtUtils codec = new JwtUtils(Keys.hmacShaKeyFor(TEST_KEY_BYTES));
        String token = codec.generateToken("user-42", Duration.ofMinutes(5));
        int signatureStart = token.lastIndexOf('.') + 1;
        char replacement = token.charAt(signatureStart) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, signatureStart)
                + replacement
                + token.substring(signatureStart + 1);

        assertThatExceptionOfType(JwtException.class)
                .isThrownBy(() -> codec.parseToken(tampered));
    }

    @Test
    void rejectsTokenSignedWithDifferentKey() {
        JwtUtils issuer = new JwtUtils(Keys.hmacShaKeyFor(TEST_KEY_BYTES));
        byte[] otherKeyBytes =
                "abcdef0123456789abcdef0123456789"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        JwtUtils verifier = new JwtUtils(Keys.hmacShaKeyFor(otherKeyBytes));
        String token = issuer.generateToken("user-42", Duration.ofMinutes(5));

        assertThatExceptionOfType(JwtException.class)
                .isThrownBy(() -> verifier.parseToken(token));
    }

    private static void assertFactoryRejects(Method factory, String value) {
        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> factory.invoke(null, value))
                .withCauseInstanceOf(IllegalArgumentException.class);
    }
}
