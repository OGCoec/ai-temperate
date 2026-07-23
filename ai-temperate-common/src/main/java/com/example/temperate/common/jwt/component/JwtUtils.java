package com.example.temperate.common.jwt.component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 使用调用方提供的 HMAC 密钥签发和校验 JWT。
 *
 * <p>该组件只负责编解码，不保存登录会话或请求级状态。
 */
@Component
public final class JwtUtils {

    private final SecretKey secretKey;

    public JwtUtils(SecretKey secretKey) {
        SecretKey providedKey =
                Objects.requireNonNull(secretKey, "JWT signing key must not be null");
        byte[] encoded = providedKey.getEncoded();
        if (encoded == null || encoded.length < 32) {
            throw new IllegalArgumentException("JWT signing key must contain at least 32 bytes");
        }
        String algorithm = providedKey.getAlgorithm();
        if (algorithm == null
                || !algorithm.toUpperCase(Locale.ROOT).startsWith("HMACSHA")) {
            throw new IllegalArgumentException("JWT signing key must use an HMAC algorithm");
        }
        this.secretKey = new SecretKeySpec(encoded, "HmacSHA256");
    }

    /**
     * 从规范的标准 Base64 文本创建 JWT codec。
     *
     * @param secretBase64 至少解码为 32 字节的标准 Base64 密钥
     * @return 使用该密钥的 JWT codec
     */
    public static JwtUtils fromBase64(String secretBase64) {
        if (secretBase64 == null || secretBase64.isBlank()) {
            throw new IllegalArgumentException("JWT Base64 secret must not be blank");
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(secretBase64);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("JWT secret must be valid standard Base64", exception);
        }

        if (!Base64.getEncoder().encodeToString(decoded).equals(secretBase64)) {
            throw new IllegalArgumentException("JWT secret must use canonical standard Base64");
        }
        if (decoded.length < 32) {
            throw new IllegalArgumentException("JWT secret must contain at least 32 bytes");
        }

        return new JwtUtils(Keys.hmacShaKeyFor(decoded));
    }

    public String generateToken(String subject, Duration timeToLive) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("JWT subject must not be blank");
        }

        Instant issuedAt = Instant.now();
        Instant expiresAt = calculateExpiration(issuedAt, timeToLive);
        return Jwts.builder()
                .subject(subject)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    public String generateToken(Map<String, ?> claims, Duration timeToLive) {
        Objects.requireNonNull(claims, "JWT claims must not be null");

        Instant issuedAt = Instant.now();
        Instant expiresAt = calculateExpiration(issuedAt, timeToLive);
        return Jwts.builder()
                .claims(claims)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    public Claims parseToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("JWT must not be blank");
        }

        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        if (claims.getExpiration() == null) {
            throw new MalformedJwtException("JWT expiration claim is required");
        }
        return claims;
    }

    /**
     * 校验签名后读取已过期令牌的声明，供刷新或登出等需要识别旧会话的流程使用。
     *
     * <p>仅放宽过期条件；格式错误、签名无效或缺少过期时间的令牌仍必须拒绝，调用方不得把该方法
     * 当作普通 API 的访问授权校验。</p>
     */
    public Claims parseTokenAllowExpired(String token) {
        try {
            return parseToken(token);
        } catch (ExpiredJwtException exception) {
            Claims claims = exception.getClaims();
            if (claims == null || claims.getExpiration() == null) {
                throw new MalformedJwtException("JWT expiration claim is required");
            }
            return claims;
        }
    }

    private static Instant calculateExpiration(Instant issuedAt, Duration timeToLive) {
        if (timeToLive == null || timeToLive.isZero() || timeToLive.isNegative()) {
            throw new IllegalArgumentException("JWT time-to-live must be positive");
        }
        try {
            return issuedAt.plus(timeToLive);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("JWT time-to-live is too large", exception);
        }
    }
}
