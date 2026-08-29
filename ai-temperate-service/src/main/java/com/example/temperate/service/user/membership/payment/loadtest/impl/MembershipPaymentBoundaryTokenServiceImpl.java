package com.example.temperate.service.user.membership.payment.loadtest.impl;

import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.service.auth.session.token.service.AuthTokenService;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentBoundaryLoadtestProperties;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentBoundaryLoadtestPolicy;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentBoundaryTokenService;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentLoadtestToken;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 该实现是来以两次批量数据库读取校验一个固定五百用户页，并通过统一 JWT 服务为八万账号分片签发十五小时测试令牌。
 *
 * <p>只有整页身份和额度完全匹配固定策略时才开始签发；令牌只作为返回值存在，不写入数据库、缓存或日志。</p>
 */
@Service
public final class MembershipPaymentBoundaryTokenServiceImpl
        implements MembershipPaymentBoundaryTokenService {

    private static final Duration ACCESS_TOKEN_TTL = Duration.ofHours(15);

    private final MembershipPaymentBoundaryLoadtestProperties properties;
    private final MembershipPaymentBoundaryLoadtestPolicy policy;
    private final AuthTokenService tokenService;
    private final UserLoginIdentityMapper identityMapper;
    private final UserMembershipQuotaMapper quotaMapper;

    public MembershipPaymentBoundaryTokenServiceImpl(
            MembershipPaymentBoundaryLoadtestProperties properties,
            MembershipPaymentBoundaryLoadtestPolicy policy,
            AuthTokenService tokenService,
            UserLoginIdentityMapper identityMapper,
            UserMembershipQuotaMapper quotaMapper) {
        this.properties = Objects.requireNonNull(properties);
        this.policy = Objects.requireNonNull(policy);
        this.tokenService = Objects.requireNonNull(tokenService);
        this.identityMapper = Objects.requireNonNull(identityMapper);
        this.quotaMapper = Objects.requireNonNull(quotaMapper);
    }

    /**
     * 先完整验证整页再签发，避免部分账号异常时向 Runner 泄露一组无法形成确定性波次的残缺令牌。
     */
    @Override
    public List<MembershipPaymentLoadtestToken> issuePage(int page) {
        requireEnabled();
        List<Long> userIds = policy.pageUserIds(page);
        validatePage(userIds);
        List<MembershipPaymentLoadtestToken> tokens = new ArrayList<>(userIds.size());
        for (long userId : userIds) {
            tokens.add(new MembershipPaymentLoadtestToken(
                    userId,
                    tokenService.issueAccessToken(userId, ACCESS_TOKEN_TTL)));
        }
        return List.copyOf(tokens);
    }

    private void validatePage(List<Long> userIds) {
        final List<AuthenticationContext> contexts;
        final List<UserMembershipQuota> quotas;
        try {
            // 每页固定执行一次身份批量读和一次额度批量读；后续逐用户核对全部只在内存中完成。
            contexts = identityMapper.findAuthenticationByIds(userIds);
            quotas = quotaMapper.findByLoginIdentityIds(userIds);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Boundary loadtest account validation is temporarily unavailable.",
                    exception);
        }

        Map<Long, AuthenticationContext> contextsById = new LinkedHashMap<>();
        for (AuthenticationContext context : safe(contexts)) {
            if (context == null
                    || contextsById.put(context.getIdentityId(), context) != null) {
                throw new IllegalStateException(
                        "Boundary loadtest account page is unavailable or duplicated.");
            }
        }
        Map<Long, UserMembershipQuota> quotasById = new LinkedHashMap<>();
        for (UserMembershipQuota quota : safe(quotas)) {
            if (quota == null
                    || quota.getLoginIdentityId() == null
                    || quotasById.put(quota.getLoginIdentityId(), quota) != null) {
                throw new IllegalStateException(
                        "Boundary loadtest membership quota page is missing or duplicated.");
            }
        }
        for (long userId : userIds) {
            AuthenticationContext context = contextsById.get(userId);
            if (context == null
                    || context.getIdentityId() != userId
                    || context.getAccountStatus() != AccountStatus.ACTIVE) {
                throw new IllegalStateException(
                        "Boundary loadtest account is unavailable.");
            }
            UserMembershipQuota quota = quotasById.get(userId);
            if (quota == null || !Objects.equals(quota.getLoginIdentityId(), userId)) {
                throw new IllegalStateException(
                        "Boundary loadtest membership quota is missing.");
            }
        }
        if (contextsById.size() != userIds.size() || quotasById.size() != userIds.size()) {
            throw new IllegalStateException(
                    "Boundary loadtest page contains foreign account or quota rows.");
        }
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new IllegalStateException("Membership payment boundary loadtest is disabled.");
        }
    }

    private static <T> List<T> safe(List<T> rows) {
        return rows == null ? List.of() : rows;
    }
}
