package com.example.temperate.service.user.membership.payment.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.service.auth.session.token.dto.result.VerifiedAccessToken;
import com.example.temperate.service.auth.session.token.service.AuthTokenService;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentBoundaryLoadtestProperties;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentLoadtestProperties;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来验证压测认证只接受白名单现有用户的未过期 AT，并实时检查账号状态与会员额度记录。
 */
final class MembershipPaymentLoadtestAccessServiceImplTest {

    private static final long USER_ID = 73014701344296960L;
    private static final String PUBLIC_ID = "AKMEmwYi80A";

    private AuthTokenService tokenService;
    private PublicIdCodec publicIdCodec;
    private UserLoginIdentityMapper identityMapper;
    private UserMembershipQuotaMapper quotaMapper;
    private MembershipPaymentLoadtestAccessService service;

    @BeforeEach
    void setUp() {
        tokenService = mock(AuthTokenService.class);
        publicIdCodec = mock(PublicIdCodec.class);
        identityMapper = mock(UserLoginIdentityMapper.class);
        quotaMapper = mock(UserMembershipQuotaMapper.class);
        service = new MembershipPaymentLoadtestAccessServiceImpl(
                new MembershipPaymentLoadtestProperties(true, List.of(USER_ID)),
                new MembershipPaymentBoundaryLoadtestProperties(false),
                new MembershipPaymentBoundaryLoadtestPolicy(),
                tokenService,
                publicIdCodec,
                identityMapper,
                quotaMapper);
    }

    @Test
    void validAllowlistedExistingAccountProducesPrincipal() {
        when(tokenService.verifyAccessToken("valid-at")).thenReturn(token(false));
        when(publicIdCodec.decode(PUBLIC_ID)).thenReturn(USER_ID);
        when(identityMapper.findAuthenticationById(USER_ID))
                .thenReturn(context(AccountStatus.ACTIVE));
        UserMembershipQuota quota = new UserMembershipQuota();
        quota.setLoginIdentityId(USER_ID);
        when(quotaMapper.findByLoginIdentityId(USER_ID)).thenReturn(quota);

        var principal = service.authenticate("valid-at");

        assertThat(principal.userId()).isEqualTo(USER_ID);
        assertThat(principal.publicId()).isEqualTo(PUBLIC_ID);
        assertThat(principal.displayName()).isEqualTo("压测用户");
    }

    @Test
    void expiredAccessTokenIsRejectedBeforeDatabaseLookup() {
        when(tokenService.verifyAccessToken("expired-at")).thenReturn(token(true));

        assertCode("expired-at", SessionAuthenticationErrorCode.ACCESS_TOKEN_INVALID);

        verifyNoInteractions(identityMapper, quotaMapper);
    }

    @Test
    void userOutsideAllowlistIsRejectedBeforeDatabaseLookup() {
        when(tokenService.verifyAccessToken("other-at")).thenReturn(token(false));
        when(publicIdCodec.decode(PUBLIC_ID)).thenReturn(99L);

        assertCode("other-at", SessionAuthenticationErrorCode.ACCOUNT_UNAVAILABLE);

        verifyNoInteractions(identityMapper, quotaMapper);
    }

    @Test
    void missingMembershipQuotaMakesExistingAccountUnavailableForLoadtest() {
        when(tokenService.verifyAccessToken("valid-at")).thenReturn(token(false));
        when(publicIdCodec.decode(PUBLIC_ID)).thenReturn(USER_ID);
        when(identityMapper.findAuthenticationById(USER_ID))
                .thenReturn(context(AccountStatus.ACTIVE));
        when(quotaMapper.findByLoginIdentityId(USER_ID)).thenReturn(null);

        assertCode("valid-at", SessionAuthenticationErrorCode.ACCOUNT_UNAVAILABLE);
    }

    @Test
    void fixedBoundaryUserAuthenticatesOnlyWhileIndependentGateIsEnabled() {
        long boundaryUserId = new MembershipPaymentBoundaryLoadtestPolicy().firstUserId();
        service = new MembershipPaymentLoadtestAccessServiceImpl(
                new MembershipPaymentLoadtestProperties(true, List.of(USER_ID)),
                new MembershipPaymentBoundaryLoadtestProperties(true),
                new MembershipPaymentBoundaryLoadtestPolicy(),
                tokenService,
                publicIdCodec,
                identityMapper,
                quotaMapper);
        when(tokenService.verifyAccessToken("boundary-at")).thenReturn(token(false));
        when(publicIdCodec.decode(PUBLIC_ID)).thenReturn(boundaryUserId);
        when(identityMapper.findAuthenticationById(boundaryUserId))
                .thenReturn(new AuthenticationContext(
                        boundaryUserId, "unused", 1L, AccountStatus.ACTIVE, "边界用户"));
        UserMembershipQuota quota = new UserMembershipQuota();
        quota.setLoginIdentityId(boundaryUserId);
        when(quotaMapper.findByLoginIdentityId(boundaryUserId)).thenReturn(quota);

        assertThat(service.authenticate("boundary-at").userId()).isEqualTo(boundaryUserId);
    }

    @Test
    void neighboringSignedUserIsRejectedBeforeDatabaseReadWhenBoundaryGateIsEnabled() {
        MembershipPaymentBoundaryLoadtestPolicy policy =
                new MembershipPaymentBoundaryLoadtestPolicy();
        service = new MembershipPaymentLoadtestAccessServiceImpl(
                new MembershipPaymentLoadtestProperties(true, List.of(USER_ID)),
                new MembershipPaymentBoundaryLoadtestProperties(true),
                policy,
                tokenService,
                publicIdCodec,
                identityMapper,
                quotaMapper);
        when(tokenService.verifyAccessToken("neighbor-at")).thenReturn(token(false));
        when(publicIdCodec.decode(PUBLIC_ID)).thenReturn(policy.lastUserId() + 1L);

        assertCode("neighbor-at", SessionAuthenticationErrorCode.ACCOUNT_UNAVAILABLE);

        verifyNoInteractions(identityMapper, quotaMapper);
    }

    private void assertCode(String rawToken, SessionAuthenticationErrorCode expected) {
        assertThatThrownBy(() -> service.authenticate(rawToken))
                .isInstanceOfSatisfying(SessionAuthenticationException.class,
                        exception -> assertThat(exception.code()).isEqualTo(expected));
    }

    private static VerifiedAccessToken token(boolean expired) {
        return new VerifiedAccessToken(
                PUBLIC_ID,
                "A".repeat(38),
                2,
                Instant.parse("2026-08-20T12:00:00Z"),
                Instant.parse("2026-08-20T12:10:00Z"),
                expired);
    }

    private static AuthenticationContext context(AccountStatus status) {
        return new AuthenticationContext(
                USER_ID, "unused", 1L, status, "压测用户");
    }
}
