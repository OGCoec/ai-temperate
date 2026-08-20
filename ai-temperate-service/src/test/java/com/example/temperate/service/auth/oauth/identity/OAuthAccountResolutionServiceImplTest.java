package com.example.temperate.service.auth.oauth.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.model.user.entity.UserLoginIdentity;
import com.example.temperate.service.auth.oauth.domain.OAuthProofType;
import com.example.temperate.service.auth.oauth.domain.OAuthProvider;
import com.example.temperate.service.auth.oauth.domain.TrustedOAuthIdentity;
import com.example.temperate.service.auth.oauth.identity.impl.OAuthAccountResolutionServiceImpl;
import com.example.temperate.service.registration.component.normalizer.RegistrationInputNormalizer;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证第三方账号解析严格遵循 Subject 优先、已验证邮箱自动合并和缺手机号补验顺序。
 */
class OAuthAccountResolutionServiceImplTest {

    private UserLoginIdentityMapper mapper;
    private OAuthSubjectBindingStrategy github;
    private OAuthAccountResolutionService service;

    @BeforeEach
    void setUp() {
        mapper = mock(UserLoginIdentityMapper.class);
        github = mock(OAuthSubjectBindingStrategy.class);
        when(github.provider()).thenReturn(OAuthProvider.GITHUB);
        service = new OAuthAccountResolutionServiceImpl(
                mapper,
                new RegistrationInputNormalizer(),
                new OAuthSubjectBindingRegistry(Map.of("github", github)));
    }

    @Test
    void subjectMatchWinsEvenWhenProviderEmailChanged() {
        UserLoginIdentity bound = identity(41L, "old@example.com", "+12025550141");
        when(github.findBySubject("9001")).thenReturn(bound);
        when(mapper.findAuthenticationById(41L)).thenReturn(context(41L, "+12025550141"));

        OAuthAccountDecision decision = service.resolve(identity("new@example.com"));

        assertEquals(41L, decision.existingIdentityId());
        assertFalse(decision.phoneRequired());
        assertEquals(OAuthAccountDecisionType.AUTHENTICATE, decision.type());
    }

    @Test
    void verifiedEmailAutomaticallyTargetsExistingPasswordAccount() {
        when(github.findBySubject(anyString())).thenReturn(null);
        UserLoginIdentity passwordAccount = identity(52L, "member@example.com", "+12025550152");
        when(mapper.findByNormalizedEmail("member@example.com")).thenReturn(passwordAccount);
        when(github.subjectOf(passwordAccount)).thenReturn(null);
        when(mapper.findAuthenticationById(52L)).thenReturn(context(52L, "+12025550152"));

        OAuthAccountDecision decision = service.resolve(identity("Member@Example.com"));

        assertEquals(52L, decision.existingIdentityId());
        assertFalse(decision.phoneRequired());
    }

    @Test
    void newEmailRequiresPhoneBeforeRegistration() {
        when(github.findBySubject(anyString())).thenReturn(null);
        when(mapper.findByNormalizedEmail("new@example.com")).thenReturn(null);

        OAuthAccountDecision decision = service.resolve(identity("new@example.com"));

        assertEquals(0L, decision.existingIdentityId());
        assertTrue(decision.phoneRequired());
        assertEquals(OAuthAccountDecisionType.PHONE_REQUIRED, decision.type());
    }

    private static TrustedOAuthIdentity identity(String email) {
        return new TrustedOAuthIdentity(
                OAuthProvider.GITHUB,
                "9001",
                email,
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

    private static AuthenticationContext context(long id, String phone) {
        return new AuthenticationContext(
                id, null, 1L, AccountStatus.ACTIVE, "用户", "old@example.com", phone, false);
    }
}
