package com.example.temperate.service.admin.login.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.config.AdminConfiguration;
import com.example.temperate.service.admin.config.AdminConfigurationService;
import com.example.temperate.service.admin.config.AdminStatus;
import com.example.temperate.service.humanverification.HumanVerificationService;
import com.example.temperate.service.humanverification.HumanVerificationServiceRegistry;
import com.example.temperate.service.humanverification.HumanVerificationType;
import com.example.temperate.service.admin.login.AdminLoginAccess;
import com.example.temperate.service.admin.login.AdminLoginCompleteCommand;
import com.example.temperate.service.admin.login.AdminLoginFlow;
import com.example.temperate.service.admin.login.AdminLoginFlowStore;
import com.example.temperate.service.admin.login.ProtectedAdminLoginAccess;
import com.example.temperate.service.admin.session.AdminSessionIssue;
import com.example.temperate.service.admin.session.AdminSessionProfile;
import com.example.temperate.service.admin.session.AdminSessionService;
import com.example.temperate.service.auth.login.limit.enums.LoginLimitDecision;
import com.example.temperate.service.auth.login.limit.service.LoginRateLimitService;
import com.example.temperate.service.registration.component.normalizer.RegistrationInputNormalizer;
import com.example.temperate.service.registration.component.token.RegistrationTokenGenerator;
import com.example.temperate.service.admin.security.AdminSecretProtector;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * 验证管理员登录必须先完成 hCaptcha，再同时匹配邮箱、手机号和密码并签发单一会话 Token。
 */
@ExtendWith(MockitoExtension.class)
class AdminLoginServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-07-23T13:00:00Z");

    @Mock AdminConfigurationService configurationService;
    @Mock AdminLoginFlowStore flowStore;
    @Mock AdminSecretProtector protector;
    @Mock HumanVerificationServiceRegistry humanVerificationServices;
    @Mock HumanVerificationService hcaptcha;
    @Mock RegistrationInputNormalizer normalizer;
    @Mock PasswordEncoder passwordEncoder;
    @Mock LoginRateLimitService rateLimitService;
    @Mock AdminSessionService sessionService;
    @Mock RegistrationTokenGenerator tokenGenerator;

    @Test
    void expandedIpv6LoginIssuesSessionWithoutInputClassificationFailure() {
        AdminLoginAccess access =
                new AdminLoginAccess(
                        "flow-token",
                        "csrf",
                        "challenge",
                        "device",
                        "2001:0DB8:0000:0000:0000:0000:0000:0001");
        ProtectedAdminLoginAccess protectedAccess =
                new ProtectedAdminLoginAccess(null, null, null, null);
        AdminLoginFlow flow = new AdminLoginFlow(protectedAccess, NOW, NOW.plusSeconds(600));
        AdminConfiguration configuration = new AdminConfiguration(
                1, AdminStatus.ACTIVE, "admin@example.test", "US", "+12164202316",
                "{bcrypt}$2a$10$" + "A".repeat(53), NOW, NOW);
        AdminSessionIssue issue = new AdminSessionIssue(
                "session-token",
                new AdminSessionProfile(
                        configuration.email(),
                        configuration.countryIso2(),
                        configuration.phoneE164(),
                        NOW.plus(Duration.ofHours(6))));
        when(configurationService.requireActive()).thenReturn(configuration);
        when(protector.protectLogin(access)).thenReturn(protectedAccess);
        when(flowStore.getRequired(protectedAccess, NOW)).thenReturn(flow);
        when(humanVerificationServices.getRequired(HumanVerificationType.HCAPTCHA))
                .thenReturn(hcaptcha);
        when(hcaptcha.verify(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
        when(normalizer.normalizeEmail("admin@example.test")).thenReturn("admin@example.test");
        when(normalizer.normalizePhone("US", "+12164202316")).thenReturn("+12164202316");
        when(rateLimitService.check(org.mockito.ArgumentMatchers.any()))
                .thenReturn(LoginLimitDecision.ALLOWED);
        when(passwordEncoder.matches("password", configuration.passwordHash())).thenReturn(true);
        when(sessionService.issue("device")).thenReturn(issue);
        AdminLoginServiceImpl service = new AdminLoginServiceImpl(
                configurationService,
                flowStore,
                protector,
                humanVerificationServices,
                normalizer,
                passwordEncoder,
                rateLimitService,
                sessionService,
                tokenGenerator,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(10));

        StepVerifier.create(service.complete(new AdminLoginCompleteCommand(
                        access,
                        "admin@example.test",
                        "US",
                        "+12164202316",
                        "password",
                        "hcaptcha-token")))
                .assertNext(result -> assertThat(result.rawToken()).isEqualTo("session-token"))
                .verifyComplete();

        verify(humanVerificationServices).getRequired(HumanVerificationType.HCAPTCHA);
        verify(hcaptcha).verify(org.mockito.ArgumentMatchers.argThat(command ->
                "hcaptcha-token".equals(command.responseToken())
                        && access.canonicalIp().equals(command.canonicalClientIp())
                        && access.challengeId().equals(command.challengeId())
                        && command.expectedAction().isEmpty()));
        verify(flowStore).consume(protectedAccess);
    }

    @Test
    void hcaptchaFailureDoesNotCompareCredentialsOrIssueSession() {
        AdminLoginAccess access =
                new AdminLoginAccess("flow-token", "csrf", "challenge", "device", "127.0.0.1");
        ProtectedAdminLoginAccess protectedAccess =
                new ProtectedAdminLoginAccess(null, null, null, null);
        AdminLoginFlow flow = new AdminLoginFlow(protectedAccess, NOW, NOW.plusSeconds(600));
        AdminConfiguration configuration = new AdminConfiguration(
                1,
                AdminStatus.ACTIVE,
                "admin@example.test",
                "US",
                "+12164202316",
                "{bcrypt}$2a$10$" + "A".repeat(53),
                NOW,
                NOW);
        when(configurationService.requireActive()).thenReturn(configuration);
        when(protector.protectLogin(access)).thenReturn(protectedAccess);
        when(flowStore.getRequired(protectedAccess, NOW)).thenReturn(flow);
        when(humanVerificationServices.getRequired(HumanVerificationType.HCAPTCHA))
                .thenReturn(hcaptcha);
        when(hcaptcha.verify(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.error(
                new AdminException(
                        AdminErrorCode.HCAPTCHA_REJECTED,
                        "Human verification was rejected.")));
        AdminLoginServiceImpl service = new AdminLoginServiceImpl(
                configurationService,
                flowStore,
                protector,
                humanVerificationServices,
                normalizer,
                passwordEncoder,
                rateLimitService,
                sessionService,
                tokenGenerator,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(10));

        StepVerifier.create(service.complete(new AdminLoginCompleteCommand(
                        access,
                        "admin@example.test",
                        "US",
                        "+12164202316",
                        "password",
                        "hcaptcha-token")))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(AdminException.class);
                    assertThat(((AdminException) error).code())
                            .isEqualTo(AdminErrorCode.HCAPTCHA_REJECTED);
                })
                .verify();

        verify(humanVerificationServices).getRequired(HumanVerificationType.HCAPTCHA);
        verify(flowStore, never()).consume(protectedAccess);
        verifyNoInteractions(normalizer, passwordEncoder, rateLimitService, sessionService);
    }
}
