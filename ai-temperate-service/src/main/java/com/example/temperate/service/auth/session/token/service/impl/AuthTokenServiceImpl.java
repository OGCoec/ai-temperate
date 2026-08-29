package com.example.temperate.service.auth.session.token.service.impl;

import cn.hutool.core.lang.id.NanoId;
import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.common.jwt.component.JwtUtils;
import com.example.temperate.service.auth.session.token.dto.result.VerifiedAccessToken;
import com.example.temperate.service.auth.session.token.service.AuthTokenService;
import io.jsonwebtoken.Claims;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 会话令牌的签发与边界校验实现。
 *
 * <p>访问令牌只携带 Base64URL 公共用户标识、随机令牌标识和版本号；手机号、邮箱等可变
 * 资料不进入令牌，避免令牌有效期内携带过期或超出认证所需的个人信息。</p>
 *
 * <p>JWT 解析结果仍属于外部边界数据，只有标识、版本和时间字段同时满足规范后，才会转换为
 * {@link VerifiedAccessToken} 供后续业务使用。</p>
 */
@Service
public final class AuthTokenServiceImpl implements AuthTokenService {

    static final Duration DEFAULT_ACCESS_TOKEN_TTL = Duration.ofMinutes(10);
    private static final int TOKEN_SCHEMA_VERSION = 2;
    private static final int NANO_ID_LENGTH = 38;
    private static final int CSRF_RANDOM_BYTES = 32;
    private static final Pattern NANO_ID = Pattern.compile("^[A-Za-z0-9_-]{38}$");
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private final JwtUtils jwtUtils;
    private final PublicIdCodec publicIdCodec;
    private final SecureRandom secureRandom;
    private final Duration accessTokenTtl;

    @Autowired
    public AuthTokenServiceImpl(
            JwtUtils jwtUtils,
            PublicIdCodec publicIdCodec,
            @Value("${app.security.ttl.access-token:10m}") Duration accessTokenTtl) {
        this(jwtUtils, publicIdCodec, new SecureRandom(), accessTokenTtl);
    }

    AuthTokenServiceImpl(JwtUtils jwtUtils, PublicIdCodec publicIdCodec) {
        this(jwtUtils, publicIdCodec, new SecureRandom(), DEFAULT_ACCESS_TOKEN_TTL);
    }

    AuthTokenServiceImpl(
            JwtUtils jwtUtils, PublicIdCodec publicIdCodec, SecureRandom secureRandom) {
        this(jwtUtils, publicIdCodec, secureRandom, DEFAULT_ACCESS_TOKEN_TTL);
    }

    AuthTokenServiceImpl(
            JwtUtils jwtUtils,
            PublicIdCodec publicIdCodec,
            SecureRandom secureRandom,
            Duration accessTokenTtl) {
        this.jwtUtils = Objects.requireNonNull(jwtUtils, "jwtUtils must not be null");
        this.publicIdCodec = Objects.requireNonNull(
                publicIdCodec, "publicIdCodec must not be null");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom must not be null");
        this.accessTokenTtl = requireAccessTokenTtl(accessTokenTtl);
    }

    /**
     * 将内部用户 ID 转换为统一的公共 ID，并签发仅含最小认证声明的短期访问令牌。
     */
    @Override
    public String issueAccessToken(long userId) {
        return issueAccessToken(userId, accessTokenTtl);
    }

    /**
     * 使用与普通访问令牌完全相同的最小声明，只允许内部调用方显式收紧或延长有效期；该方法不会修改
     * 单例 Service 的默认 TTL，因此压测令牌不会影响正常登录签发行为。
     */
    @Override
    public String issueAccessToken(long userId, Duration timeToLive) {
        if (userId <= 0) {
            throw new IllegalArgumentException("Access token user ID must be positive.");
        }

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put(Claims.SUBJECT, publicIdCodec.encode(userId));
        claims.put(Claims.ID, newNanoId());
        claims.put("ver", TOKEN_SCHEMA_VERSION);
        return jwtUtils.generateToken(claims, requireAccessTokenTtl(timeToLive));
    }

    /**
     * 将解析出的 JWT 声明做规范化校验后转换为内部令牌对象，拒绝不完整、过时的协议版本或
     * 非规范公共 ID，防止边界数据直接影响认证决策。
     */
    @Override
    public VerifiedAccessToken verifyAccessToken(String rawAccessToken) {
        Claims claims = jwtUtils.parseTokenAllowExpired(rawAccessToken);
        String publicId = requireText("subject", claims.getSubject());
        publicIdCodec.decode(publicId);
        String tokenId = requireNanoId("token ID", claims.getId());
        int schemaVersion = requireNumber("token schema version", claims.get("ver")).intValue();
        if (schemaVersion != TOKEN_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Access token schema version is unsupported.");
        }
        if (claims.getIssuedAt() == null || claims.getExpiration() == null) {
            throw new IllegalArgumentException("Access token timestamps are required.");
        }
        Instant issuedAt = claims.getIssuedAt().toInstant();
        Instant expiresAt = claims.getExpiration().toInstant();
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("Access token expiration must follow issuance.");
        }
        return new VerifiedAccessToken(
                publicId,
                tokenId,
                schemaVersion,
                issuedAt,
                expiresAt,
                !expiresAt.isAfter(Instant.now()));
    }

    @Override
    public String newRefreshToken() {
        return newNanoId();
    }

    @Override
    public String newFlowToken() {
        return newNanoId();
    }

    /**
     * 使用密码学安全随机源生成 256 位 CSRF 原始值，并以无填充 Base64URL 编码以安全地传输
     * 到 Cookie 与请求头之间；服务端持久化的是其受保护标识而不是原始值。
     */
    @Override
    public String newCsrfToken() {
        byte[] bytes = new byte[CSRF_RANDOM_BYTES];
        secureRandom.nextBytes(bytes);
        return BASE64_URL.encodeToString(bytes);
    }

    private static String newNanoId() {
        return NanoId.randomNanoId(NANO_ID_LENGTH);
    }

    private static String requireNanoId(String name, String value) {
        String valid = requireText(name, value);
        if (!NANO_ID.matcher(valid).matches()) {
            throw new IllegalArgumentException(name + " must be a 38-character NanoID.");
        }
        return valid;
    }

    private static String requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    private static Number requireNumber(String name, Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " is required.");
        }
        return number;
    }

    private static Duration requireAccessTokenTtl(Duration value) {
        Duration valid = Objects.requireNonNull(value, "accessTokenTtl must not be null");
        if (valid.isNegative() || valid.isZero()) {
            throw new IllegalArgumentException("Access token TTL must be positive.");
        }
        return valid;
    }
}
