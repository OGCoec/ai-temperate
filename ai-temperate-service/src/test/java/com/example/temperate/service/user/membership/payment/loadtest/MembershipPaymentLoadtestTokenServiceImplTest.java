package com.example.temperate.service.user.membership.payment.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.common.jwt.component.JwtUtils;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.service.auth.session.token.service.AuthTokenService;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentLoadtestProperties;
import com.example.temperate.service.user.membership.payment.loadtest.impl.MembershipPaymentLoadtestTokenServiceImpl;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来锁定本机压测 Token 必须由应用现有 JWT 签发器生成，并且只服务四个既有可用账号。
 */
final class MembershipPaymentLoadtestTokenServiceImplTest {

    private static final List<Long> USER_IDS = List.of(
            73014701344296960L,
            72659006262480896L,
            76721355290185728L,
            74891801495998464L);

    private AuthTokenService tokenService;
    private UserLoginIdentityMapper identityMapper;
    private UserMembershipQuotaMapper quotaMapper;
    private JwtUtils jwtUtils;
    private PublicIdCodec publicIdCodec;
    private MembershipPaymentLoadtestTokenService service;

    @BeforeEach
    void setUp() {
        tokenService = mock(AuthTokenService.class);
        identityMapper = mock(UserLoginIdentityMapper.class);
        quotaMapper = mock(UserMembershipQuotaMapper.class);
        jwtUtils = mock(JwtUtils.class);
        publicIdCodec = mock(PublicIdCodec.class);
        service = new MembershipPaymentLoadtestTokenServiceImpl(
                new MembershipPaymentLoadtestProperties(true, USER_IDS),
                tokenService,
                identityMapper,
                quotaMapper,
                jwtUtils,
                publicIdCodec);
        List<AuthenticationContext> contexts = new ArrayList<>();
        List<UserMembershipQuota> quotas = new ArrayList<>();
        for (long userId : USER_IDS) {
            contexts.add(new AuthenticationContext(
                    userId, "unused", 1L, AccountStatus.ACTIVE, "压测用户"));
            UserMembershipQuota quota = new UserMembershipQuota();
            quota.setLoginIdentityId(userId);
            quotas.add(quota);
            when(tokenService.issueAccessToken(userId)).thenReturn("signed-token-" + userId);
        }
        when(identityMapper.findAuthenticationByIds(USER_IDS)).thenReturn(contexts);
        when(quotaMapper.findByLoginIdentityIds(USER_IDS)).thenReturn(quotas);
        when(identityMapper.findAuthenticationByIds(List.of(USER_IDS.get(0))))
                .thenReturn(List.of(contexts.get(0)));
        when(quotaMapper.findByLoginIdentityIds(List.of(USER_IDS.get(0))))
                .thenReturn(List.of(quotas.get(0)));
    }

    @Test
    void issuesSignedTokensForAllExistingAllowlistedUsers() {
        List<MembershipPaymentLoadtestToken> result = service.issueForAllowlistedUsers();

        assertThat(result).extracting(MembershipPaymentLoadtestToken::userId)
                .containsExactlyElementsOf(USER_IDS);
        assertThat(result).extracting(MembershipPaymentLoadtestToken::accessToken)
                .containsExactly(
                        "signed-token-73014701344296960",
                        "signed-token-72659006262480896",
                        "signed-token-76721355290185728",
                        "signed-token-74891801495998464");
    }

    @Test
    void issuesExpiredSignedTokenForTheFirstApprovedUser() {
        when(publicIdCodec.encode(USER_IDS.get(0))).thenReturn("AAAiSkCJdcA");
        when(jwtUtils.generateToken(anyMap(), eq(Duration.ofMillis(1))))
                .thenReturn("expired-signed-token");

        assertThat(service.issueExpiredToken()).isEqualTo("expired-signed-token");
        verify(jwtUtils).generateToken(anyMap(), eq(Duration.ofMillis(1)));
    }

    @Test
    void issuesSignedTokenWhoseIdIsOutsideTheAllowlist() {
        when(tokenService.issueAccessToken(Long.MAX_VALUE))
                .thenReturn("non-allowlisted-token");

        MembershipPaymentLoadtestToken result = service.issueNonAllowlistedToken();

        assertThat(result.userId()).isEqualTo(Long.MAX_VALUE);
        assertThat(result.accessToken()).isEqualTo("non-allowlisted-token");
    }

    @Test
    void rejectsAnUnavailableAccountBeforeIssuingItsToken() {
        List<AuthenticationContext> contexts = USER_IDS.stream()
                .map(userId -> new AuthenticationContext(
                        userId,
                        "unused",
                        1L,
                        userId.equals(USER_IDS.get(2))
                                ? AccountStatus.DISABLED
                                : AccountStatus.ACTIVE,
                        "压测用户"))
                .toList();
        when(identityMapper.findAuthenticationByIds(USER_IDS)).thenReturn(contexts);

        assertThatThrownBy(() -> service.issueForAllowlistedUsers())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable");
    }

    @Test
    void rejectsAUserWithoutMembershipQuota() {
        List<UserMembershipQuota> quotas = USER_IDS.stream()
                .filter(userId -> !userId.equals(USER_IDS.get(1)))
                .map(userId -> {
                    UserMembershipQuota quota = new UserMembershipQuota();
                    quota.setLoginIdentityId(userId);
                    return quota;
                })
                .toList();
        when(quotaMapper.findByLoginIdentityIds(USER_IDS)).thenReturn(quotas);

        assertThatThrownBy(() -> service.issueForAllowlistedUsers())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quota");
    }

    @Test
    void disabledLoadtestNeverIssuesTokens() {
        MembershipPaymentLoadtestTokenService disabled = new MembershipPaymentLoadtestTokenServiceImpl(
                new MembershipPaymentLoadtestProperties(false, List.of()),
                tokenService,
                identityMapper,
                quotaMapper,
                jwtUtils,
                publicIdCodec);

        assertThatThrownBy(disabled::issueForAllowlistedUsers)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
        verifyNoInteractions(tokenService, identityMapper, quotaMapper, jwtUtils, publicIdCodec);
    }
}
