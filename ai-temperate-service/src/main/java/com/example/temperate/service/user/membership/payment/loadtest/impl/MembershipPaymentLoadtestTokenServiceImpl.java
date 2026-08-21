package com.example.temperate.service.user.membership.payment.loadtest.impl;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.common.jwt.component.JwtUtils;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.service.auth.session.token.service.AuthTokenService;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentLoadtestProperties;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentLoadtestToken;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentLoadtestTokenService;
import io.jsonwebtoken.Claims;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import org.springframework.stereotype.Service;

/**
 * 该实现是来在本机压测启动阶段校验四个既有账号，并通过正式 JWT 签发器生成短期 Access Token。
 *
 * <p>白名单账号通过身份与额度两条批量 SQL 预检，签发只用于本机调试；该实现不保存令牌、不创建账号、
 * 不改变账号状态或会员额度。</p>
 */
@Service
public final class MembershipPaymentLoadtestTokenServiceImpl
        implements MembershipPaymentLoadtestTokenService {

    private static final long NON_ALLOWLISTED_TEST_USER_ID = Long.MAX_VALUE;
    private static final String EXPIRED_TOKEN_ID = "A".repeat(38);

    private final MembershipPaymentLoadtestProperties properties;
    private final AuthTokenService tokenService;
    private final UserLoginIdentityMapper identityMapper;
    private final UserMembershipQuotaMapper quotaMapper;
    private final JwtUtils jwtUtils;
    private final PublicIdCodec publicIdCodec;

    public MembershipPaymentLoadtestTokenServiceImpl(
            MembershipPaymentLoadtestProperties properties,
            AuthTokenService tokenService,
            UserLoginIdentityMapper identityMapper,
            UserMembershipQuotaMapper quotaMapper,
            JwtUtils jwtUtils,
            PublicIdCodec publicIdCodec) {
        this.properties = Objects.requireNonNull(properties);
        this.tokenService = Objects.requireNonNull(tokenService);
        this.identityMapper = Objects.requireNonNull(identityMapper);
        this.quotaMapper = Objects.requireNonNull(quotaMapper);
        this.jwtUtils = Objects.requireNonNull(jwtUtils);
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
    }

    /**
     * 使用正式 JWT 密钥签发一毫秒令牌并在返回前等待其过期，确保测试区分“过期”与“伪造签名”。
     */
    @Override
    public String issueExpiredToken() {
        if (!properties.enabled()) {
            throw new IllegalStateException("Membership payment loadtest is disabled.");
        }
        long userId = properties.allowedUserIds().get(0);
        validateAccount(userId);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put(Claims.SUBJECT, publicIdCodec.encode(userId));
        claims.put(Claims.ID, EXPIRED_TOKEN_ID);
        claims.put("ver", 2);
        String token = jwtUtils.generateToken(claims, Duration.ofMillis(1));
        LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        return token;
    }

    /**
     * 白名单判断先于数据库查询，因此负向 Token 使用确定性未授权 ID，避免依赖本机是否恰好存在第五个账号。
     */
    @Override
    public MembershipPaymentLoadtestToken issueNonAllowlistedToken() {
        if (!properties.enabled()) {
            throw new IllegalStateException("Membership payment loadtest is disabled.");
        }
        return new MembershipPaymentLoadtestToken(
                NON_ALLOWLISTED_TEST_USER_ID,
                tokenService.issueAccessToken(NON_ALLOWLISTED_TEST_USER_ID));
    }

    @Override
    public List<MembershipPaymentLoadtestToken> issueForAllowlistedUsers() {
        if (!properties.enabled()) {
            throw new IllegalStateException("Membership payment loadtest is disabled.");
        }

        List<Long> userIds = properties.allowedUserIds();
        validateAccounts(userIds);
        List<MembershipPaymentLoadtestToken> tokens = new ArrayList<>(userIds.size());
        for (long userId : userIds) {
            // 令牌只能由统一签名服务产生；直接修改 JWT 中间段会失去签名校验，不能作为测试凭据。
            tokens.add(new MembershipPaymentLoadtestToken(
                    userId,
                    tokenService.issueAccessToken(userId)));
        }
        return List.copyOf(tokens);
    }

    private void validateAccount(long userId) {
        validateAccounts(List.of(userId));
    }

    private void validateAccounts(List<Long> userIds) {
        final List<AuthenticationContext> contexts;
        final List<UserMembershipQuota> quotas;
        try {
            // 两个集合查询各执行一次数据库 I/O，循环只在内存中核对四个固定白名单账号。
            contexts = identityMapper.findAuthenticationByIds(userIds);
            quotas = quotaMapper.findByLoginIdentityIds(userIds);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Membership payment loadtest account validation is temporarily unavailable.",
                    exception);
        }
        Map<Long, AuthenticationContext> contextsById = new LinkedHashMap<>();
        for (AuthenticationContext context : contexts) {
            contextsById.put(context.getIdentityId(), context);
        }
        Map<Long, UserMembershipQuota> quotasById = new LinkedHashMap<>();
        for (UserMembershipQuota quota : quotas) {
            if (quota.getLoginIdentityId() != null) {
                quotasById.put(quota.getLoginIdentityId(), quota);
            }
        }
        for (long userId : userIds) {
            AuthenticationContext context = contextsById.get(userId);
            if (context == null
                    || context.getIdentityId() != userId
                    || context.getAccountStatus() != AccountStatus.ACTIVE) {
                throw new IllegalStateException(
                        "Allowlisted account is unavailable for loadtest.");
            }
            UserMembershipQuota quota = quotasById.get(userId);
            if (quota == null || quota.getLoginIdentityId() == null
                    || quota.getLoginIdentityId() != userId) {
                throw new IllegalStateException(
                        "Allowlisted account membership quota is missing.");
            }
        }
    }
}
