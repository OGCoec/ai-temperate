package com.example.temperate.service.user.membership.payment.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.service.auth.session.token.service.AuthTokenService;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentBoundaryLoadtestProperties;
import com.example.temperate.service.user.membership.payment.loadtest.impl.MembershipPaymentBoundaryTokenServiceImpl;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来验证八万固定用户只能按一百六十个五百人分页批量校验并签发十五小时令牌。
 */
final class MembershipPaymentBoundaryTokenServiceImplTest {

    private MembershipPaymentBoundaryLoadtestPolicy policy;
    private AuthTokenService tokenService;
    private UserLoginIdentityMapper identityMapper;
    private UserMembershipQuotaMapper quotaMapper;

    @BeforeEach
    void setUp() {
        policy = new MembershipPaymentBoundaryLoadtestPolicy();
        tokenService = mock(AuthTokenService.class);
        identityMapper = mock(UserLoginIdentityMapper.class);
        quotaMapper = mock(UserMembershipQuotaMapper.class);
    }

    @Test
    void pageReturnsCanonicalFiveHundredUsersInOrderWithTwoBulkReads() {
        List<Long> ids = policy.pageUserIds(159);
        when(identityMapper.findAuthenticationByIds(ids)).thenReturn(activeContexts(ids));
        when(quotaMapper.findByLoginIdentityIds(ids)).thenReturn(quotas(ids));
        when(tokenService.issueAccessToken(any(Long.class), any(Duration.class)))
                .thenAnswer(invocation -> "token-" + invocation.getArgument(0));
        MembershipPaymentBoundaryTokenService service = service(true);

        List<MembershipPaymentLoadtestToken> result = service.issuePage(159);

        assertThat(result).hasSize(500);
        assertThat(result).extracting(MembershipPaymentLoadtestToken::userId)
                .containsExactlyElementsOf(ids);
        assertThat(result).extracting(MembershipPaymentLoadtestToken::accessToken)
                .startsWith("token-" + ids.get(0))
                .endsWith("token-" + ids.get(ids.size() - 1));
        verify(identityMapper).findAuthenticationByIds(ids);
        verify(quotaMapper).findByLoginIdentityIds(ids);
        verify(tokenService).issueAccessToken(ids.get(0), Duration.ofHours(15));
    }

    @Test
    void disabledGateRejectsBeforeReadsOrSigning() {
        MembershipPaymentBoundaryTokenService service = service(false);

        assertThatThrownBy(() -> service.issuePage(0))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(identityMapper, quotaMapper, tokenService);
    }

    @Test
    void missingIdentityRejectsEntirePageBeforeSigning() {
        List<Long> ids = policy.pageUserIds(0);
        List<AuthenticationContext> contexts = new ArrayList<>(activeContexts(ids));
        contexts.remove(contexts.size() - 1);
        when(identityMapper.findAuthenticationByIds(ids)).thenReturn(contexts);
        when(quotaMapper.findByLoginIdentityIds(ids)).thenReturn(quotas(ids));

        assertThatThrownBy(() -> service(true).issuePage(0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable");

        verify(tokenService, never()).issueAccessToken(any(Long.class), any(Duration.class));
    }

    @Test
    void inactiveIdentityRejectsEntirePageBeforeSigning() {
        List<Long> ids = policy.pageUserIds(7);
        List<AuthenticationContext> contexts = new ArrayList<>(activeContexts(ids));
        long inactiveId = ids.get(250);
        contexts.set(250, context(inactiveId, AccountStatus.DISABLED));
        when(identityMapper.findAuthenticationByIds(ids)).thenReturn(contexts);
        when(quotaMapper.findByLoginIdentityIds(ids)).thenReturn(quotas(ids));

        assertThatThrownBy(() -> service(true).issuePage(7))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable");

        verify(tokenService, never()).issueAccessToken(any(Long.class), any(Duration.class));
    }

    @Test
    void missingQuotaRejectsEntirePageBeforeSigning() {
        List<Long> ids = policy.pageUserIds(2);
        List<UserMembershipQuota> rows = new ArrayList<>(quotas(ids));
        rows.remove(0);
        when(identityMapper.findAuthenticationByIds(ids)).thenReturn(activeContexts(ids));
        when(quotaMapper.findByLoginIdentityIds(ids)).thenReturn(rows);

        assertThatThrownBy(() -> service(true).issuePage(2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quota");

        verify(tokenService, never()).issueAccessToken(any(Long.class), any(Duration.class));
    }

    @Test
    void pageOutsideFixedRangeIsRejectedBeforeReads() {
        assertThatThrownBy(() -> service(true).issuePage(160))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(identityMapper, quotaMapper, tokenService);
    }

    private MembershipPaymentBoundaryTokenService service(boolean enabled) {
        return new MembershipPaymentBoundaryTokenServiceImpl(
                new MembershipPaymentBoundaryLoadtestProperties(enabled),
                policy,
                tokenService,
                identityMapper,
                quotaMapper);
    }

    private static List<AuthenticationContext> activeContexts(List<Long> ids) {
        return ids.stream().map(id -> context(id, AccountStatus.ACTIVE)).toList();
    }

    private static AuthenticationContext context(long id, AccountStatus status) {
        return new AuthenticationContext(id, "unused", 1L, status, "Boundary " + id);
    }

    private static List<UserMembershipQuota> quotas(List<Long> ids) {
        return ids.stream().map(id -> {
            UserMembershipQuota quota = new UserMembershipQuota();
            quota.setLoginIdentityId(id);
            return quota;
        }).toList();
    }
}
