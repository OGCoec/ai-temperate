package com.example.temperate.service.auth.oauth.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.mapper.user.profile.UserProfileMapper;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.auth.enums.RegistrationSource;
import com.example.temperate.model.user.entity.UserLoginIdentity;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceFilter;
import com.example.temperate.service.auth.oauth.domain.OAuthProofType;
import com.example.temperate.service.auth.oauth.domain.OAuthProvider;
import com.example.temperate.service.auth.oauth.domain.TrustedOAuthIdentity;
import com.example.temperate.service.auth.oauth.identity.impl.OAuthAccountFinalizationServiceImpl;
import com.example.temperate.service.auth.oauth.identity.impl.GithubOAuthSubjectBindingStrategy;
import com.example.temperate.service.registration.component.executor.RegistrationAfterCommitExecutor;
import com.example.temperate.service.registration.component.id.RegistrationIdGenerator;
import com.example.temperate.service.registration.component.normalizer.RegistrationInputNormalizer;
import com.example.temperate.service.user.membership.MembershipQuotaPlan;
import com.example.temperate.service.user.membership.MembershipQuotaPlanService;
import com.example.temperate.service.user.profile.cache.UserProfileCacheInvalidationExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证 OAuth 最终事务会自动绑定同邮箱账号，并以空密码创建完成手机验证的新账号。
 */
class OAuthAccountFinalizationServiceImplTest {

    private UserLoginIdentityMapper identityMapper;
    private UserProfileMapper profileMapper;
    private UserMembershipQuotaMapper quotaMapper;
    private OAuthSubjectBindingStrategy google;
    private OAuthSubjectBindingStrategy github;
    private OAuthAccountFinalizationService service;

    @BeforeEach
    void setUp() {
        identityMapper = mock(UserLoginIdentityMapper.class);
        profileMapper = mock(UserProfileMapper.class);
        quotaMapper = mock(UserMembershipQuotaMapper.class);
        google = mock(OAuthSubjectBindingStrategy.class);
        when(google.provider()).thenReturn(OAuthProvider.GOOGLE);
        when(google.registrationSource())
                .thenReturn(com.example.temperate.model.auth.enums.RegistrationSource.GOOGLE);
        doAnswer(invocation -> {
            UserLoginIdentity identity = invocation.getArgument(0);
            identity.setGoogleSubject(invocation.getArgument(1));
            return null;
        }).when(google).applySubject(any(), any());
        github = new GithubOAuthSubjectBindingStrategy(identityMapper);
        MembershipQuotaPlanService quotaPlanService = mock(MembershipQuotaPlanService.class);
        when(quotaPlanService.getRequired(MembershipTier.FREE))
                .thenReturn(new MembershipQuotaPlan(10_000L, Duration.ofDays(30)));
        RegistrationIdGenerator idGenerator = mock(RegistrationIdGenerator.class);
        when(idGenerator.nextPositiveId()).thenReturn(77L);
        PublicIdCodec publicIdCodec = mock(PublicIdCodec.class);
        when(publicIdCodec.encode(anyLong())).thenReturn("AAAAAAAAATQ");
        RegistrationAfterCommitExecutor afterCommit = mock(RegistrationAfterCommitExecutor.class);
        UserProfileCacheInvalidationExecutor cacheInvalidator =
                mock(UserProfileCacheInvalidationExecutor.class);
        service = new OAuthAccountFinalizationServiceImpl(
                identityMapper,
                profileMapper,
                quotaMapper,
                quotaPlanService,
                idGenerator,
                publicIdCodec,
                new RegistrationInputNormalizer(),
                new OAuthSubjectBindingRegistry(Map.of(
                        "google", google,
                        "github", github)),
                afterCommit,
                mock(IdentityPresenceFilter.class),
                cacheInvalidator,
                Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void shouldBindVerifiedGoogleEmailToExistingPasswordAccount() {
        UserLoginIdentity existing = identity(18L, "member@example.com", "+12025550118");
        when(identityMapper.findByNormalizedEmailForUpdate("member@example.com"))
                .thenReturn(existing);
        when(google.subjectOf(existing)).thenReturn(null);
        when(google.bindIfAbsent(18L, "google-18")).thenReturn(1);
        when(identityMapper.findAuthenticationById(18L)).thenReturn(context(18L));

        AuthenticationContext result = service.finalizeIdentity(trusted(), null);

        assertEquals(18L, result.getIdentityId());
        verify(google).bindIfAbsent(18L, "google-18");
        verify(identityMapper).markEmailVerified(18L);
    }

    @Test
    void shouldBindVerifiedGithubEmailWithoutChangingExistingCredentials() {
        UserLoginIdentity existing = identity(19L, "member@example.com", "+12025550119");
        existing.setPasswordHash("{bcrypt}$2a$10$existing-password-hash");
        existing.setGoogleSubject("google-existing-19");
        existing.setRegistrationSource(RegistrationSource.STANDARD);
        when(identityMapper.findByNormalizedEmailForUpdate("member@example.com"))
                .thenReturn(existing);
        when(identityMapper.bindGithubSubjectIfAbsent(19L, "918273645"))
                .thenReturn(1);
        when(identityMapper.findAuthenticationById(19L)).thenReturn(context(19L));

        AuthenticationContext result = service.finalizeIdentity(trustedGithub(), null);

        assertEquals(19L, result.getIdentityId());
        assertEquals("{bcrypt}$2a$10$existing-password-hash", existing.getPasswordHash());
        assertEquals("google-existing-19", existing.getGoogleSubject());
        assertEquals(RegistrationSource.STANDARD, existing.getRegistrationSource());
        assertEquals("+12025550119", existing.getPhone());
        verify(identityMapper).bindGithubSubjectIfAbsent(19L, "918273645");
        verify(identityMapper).markEmailVerified(19L);
        verify(identityMapper, never()).fillPhoneIfAbsent(anyLong(), any());
        verify(identityMapper, never()).insertOAuthIdentityIfAbsent(any());
    }

    @Test
    void shouldTreatConcurrentBindingOfSameSubjectToSameAccountAsIdempotent() {
        UserLoginIdentity existing = identity(20L, "member@example.com", "+12025550120");
        UserLoginIdentity refreshed = identity(20L, "member@example.com", "+12025550120");
        when(identityMapper.findByNormalizedEmailForUpdate("member@example.com"))
                .thenReturn(existing);
        when(google.subjectOf(existing)).thenReturn(null);
        when(google.bindIfAbsent(20L, "google-18")).thenReturn(0);
        when(identityMapper.findByIdForUpdate(20L)).thenReturn(refreshed);
        when(google.subjectOf(refreshed)).thenReturn("google-18");
        when(identityMapper.findAuthenticationById(20L)).thenReturn(context(20L));

        AuthenticationContext result = service.finalizeIdentity(trusted(), null);

        assertEquals(20L, result.getIdentityId());
        verify(identityMapper).findByIdForUpdate(20L);
    }

    @Test
    void shouldRejectConcurrentBindingWhenAccountContainsDifferentSubject() {
        UserLoginIdentity existing = identity(21L, "member@example.com", "+12025550121");
        UserLoginIdentity refreshed = identity(21L, "member@example.com", "+12025550121");
        when(identityMapper.findByNormalizedEmailForUpdate("member@example.com"))
                .thenReturn(existing);
        when(google.subjectOf(existing)).thenReturn(null);
        when(google.bindIfAbsent(21L, "google-18")).thenReturn(0);
        when(identityMapper.findByIdForUpdate(21L)).thenReturn(refreshed);
        when(google.subjectOf(refreshed)).thenReturn("google-other");

        OAuthAccountException exception = assertThrows(
                OAuthAccountException.class,
                () -> service.finalizeIdentity(trusted(), null));

        assertEquals(OAuthAccountErrorCode.ACCOUNT_CONFLICT, exception.code());
    }

    @Test
    void shouldCreateOAuthAccountWithNullablePasswordAfterPhoneProof() {
        when(identityMapper.findByNormalizedEmailForUpdate("member@example.com"))
                .thenReturn(null);
        when(identityMapper.findByNormalizedPhone("+12025550177")).thenReturn(null);
        when(identityMapper.fillPhoneIfAbsent(77L, "+12025550177")).thenReturn(1);
        when(identityMapper.insertOAuthIdentityIfAbsent(any())).thenAnswer(invocation -> {
            UserLoginIdentity identity = invocation.getArgument(0);
            assertNull(identity.getPasswordHash());
            assertEquals("google-18", identity.getGoogleSubject());
            return 1;
        });
        when(profileMapper.insert(any())).thenReturn(1);
        when(quotaMapper.insert(any())).thenReturn(1);
        when(identityMapper.findAuthenticationById(77L)).thenReturn(context(77L));

        AuthenticationContext result = service.finalizeIdentity(trusted(), "+12025550177");

        assertEquals(77L, result.getIdentityId());
        verify(identityMapper).insertOAuthIdentityIfAbsent(any());
        verify(identityMapper).fillPhoneIfAbsent(77L, "+12025550177");
    }

    @Test
    void shouldTreatConcurrentCreationOfTheSameSubjectAsIdempotent() {
        UserLoginIdentity concurrent = identity(88L, "member@example.com", "+12025550188");
        when(google.findBySubject("google-18")).thenReturn(null, concurrent);
        when(identityMapper.findByNormalizedEmailForUpdate("member@example.com"))
                .thenReturn(null);
        when(identityMapper.findByNormalizedPhone("+12025550177")).thenReturn(null);
        when(identityMapper.insertOAuthIdentityIfAbsent(any())).thenReturn(0);
        when(identityMapper.findByIdForUpdate(88L)).thenReturn(concurrent);
        when(google.subjectOf(concurrent)).thenReturn("google-18");
        when(identityMapper.findAuthenticationById(88L)).thenReturn(context(88L));

        AuthenticationContext result = service.finalizeIdentity(trusted(), "+12025550177");

        assertEquals(88L, result.getIdentityId());
        verify(profileMapper, never()).insert(any());
        verify(quotaMapper, never()).insert(any());
    }

    private static TrustedOAuthIdentity trusted() {
        return new TrustedOAuthIdentity(
                OAuthProvider.GOOGLE,
                "google-18",
                "Member@Example.com",
                true,
                OAuthProofType.GOOGLE_NATIVE_ID_TOKEN);
    }

    private static TrustedOAuthIdentity trustedGithub() {
        return new TrustedOAuthIdentity(
                OAuthProvider.GITHUB,
                "918273645",
                "Member@Example.com",
                true,
                OAuthProofType.BROWSER_AUTHORIZATION_CODE);
    }

    private static UserLoginIdentity identity(long id, String email, String phone) {
        UserLoginIdentity identity = new UserLoginIdentity();
        identity.setId(id);
        identity.setEmail(email);
        identity.setPhone(phone);
        return identity;
    }

    private static AuthenticationContext context(long id) {
        return new AuthenticationContext(
                id, null, 1L, AccountStatus.ACTIVE, "用户", "member@example.com",
                "+12025550118", false);
    }
}
