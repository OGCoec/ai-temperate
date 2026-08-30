package com.example.temperate.service.risk.logging.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.registration.verification.delivery.logging.DebugLogCapture;
import com.example.temperate.service.risk.decision.NetworkRiskAssessmentService;
import com.example.temperate.service.risk.decision.RiskAssessment;
import com.example.temperate.service.risk.domain.RiskDecision;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.observability.NetworkRiskDiagnosticContext;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthIssue;
import com.example.temperate.service.risk.preauth.domain.PreAuthRequiredException;
import com.example.temperate.service.risk.preauth.domain.PreAuthSessionBinding;
import com.example.temperate.service.risk.preauth.domain.PreAuthState;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.aop.support.AopUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * 验证网络风险 AOP 通过业务接口代理记录稳定结果，同时保持 Mono 惰性并禁止敏感凭据进入日志。
 */
class NetworkRiskDiagnosticLoggingAspectTest {

    private static final String RAW_TOKEN = "sensitive-preauth-token";
    private static final String RAW_DEVICE = "sensitive-device-id";
    private static final String RAW_SESSION = "sensitive-admin-session";

    @Test
    void logsResolveTouchAndPromotionThroughJdkProxyWithoutSensitiveValues() {
        PreAuthAccess access = access();
        PreAuthService target = mock(PreAuthService.class);
        when(target.resolve(RiskScope.ADMIN, RAW_TOKEN, RAW_DEVICE))
                .thenReturn(Optional.of(access));
        when(target.touch(any(), any())).thenReturn(false);
        when(target.promoteAuthenticated(
                        access,
                        RiskSessionType.ADMIN_SESSION,
                        RAW_SESSION,
                        Instant.parse("2026-07-26T16:06:24Z")))
                .thenReturn(new PreAuthIssue(
                        "new-sensitive-preauth-token",
                        Instant.parse("2026-07-26T22:06:24Z")));
        when(target.requireSessionBinding(
                        access,
                        RiskScope.ADMIN,
                        RiskSessionType.ADMIN_SESSION,
                        RAW_SESSION))
                .thenReturn(mock(PreAuthSessionBinding.class));
        PreAuthService service = proxy(target, PreAuthService.class);

        assertThat(AopUtils.isJdkDynamicProxy(service)).isTrue();
        try (DebugLogCapture logs =
                        DebugLogCapture.start(NetworkRiskDiagnosticLoggingAspect.class);
                NetworkRiskDiagnosticContext.Scope ignored =
                        NetworkRiskDiagnosticContext.open(
                                "trace-risk-aop", 2, "ASYNC", "network_risk_prehandle")) {
            assertThat(service.resolve(RiskScope.ADMIN, RAW_TOKEN, RAW_DEVICE))
                    .contains(access);
            assertThat(service.touch(access, Instant.parse("2026-07-26T16:06:24Z")))
                    .isFalse();
            service.promoteAuthenticated(
                    access,
                    RiskSessionType.ADMIN_SESSION,
                    RAW_SESSION,
                    Instant.parse("2026-07-26T16:06:24Z"));
            service.requireSessionBinding(
                    access,
                    RiskScope.ADMIN,
                    RiskSessionType.ADMIN_SESSION,
                    RAW_SESSION);

            assertThat(logs.joinedMessages())
                    .contains(
                            "event=preauth_resolve_completed",
                            "event=preauth_touch_completed",
                            "event=preauth_promotion_completed",
                            "event=preauth_session_binding_completed",
                            "traceId=trace-risk-aop",
                            "invocationNo=2",
                            "dispatcherType=ASYNC",
                            "scope=ADMIN",
                            "result=not_found")
                    .doesNotContain(
                            RAW_TOKEN,
                            RAW_DEVICE,
                            RAW_SESSION,
                            "new-sensitive-preauth-token",
                            "A".repeat(43),
                            "D".repeat(43));
        }
    }

    @Test
    void keepsAssessmentLazyAndLogsOnlyAfterSubscription() {
        AtomicInteger subscriptions = new AtomicInteger();
        RiskAssessment assessment = new RiskAssessment(
                RiskDecision.ALLOW,
                100,
                false,
                0,
                HmacIdentifier.fromProtectedValue("I".repeat(43)),
                HmacIdentifier.fromProtectedValue("C".repeat(43)));
        NetworkRiskAssessmentService target = (access, observation) -> Mono.defer(() -> {
            subscriptions.incrementAndGet();
            return Mono.just(assessment);
        });
        NetworkRiskAssessmentService service =
                proxy(target, NetworkRiskAssessmentService.class);

        try (DebugLogCapture logs =
                        DebugLogCapture.start(NetworkRiskDiagnosticLoggingAspect.class);
                NetworkRiskDiagnosticContext.Scope ignored =
                        NetworkRiskDiagnosticContext.open(
                                "trace-assessment", 1, "REQUEST", "network_risk_prehandle")) {
            Mono<RiskAssessment> operation = service.assess(
                    access(), mock(TrustedNetworkObservation.class));

            assertThat(subscriptions).hasValue(0);
            assertThat(logs.messages()).isEmpty();

            StepVerifier.create(operation).expectNext(assessment).verifyComplete();

            assertThat(subscriptions).hasValue(1);
            assertThat(logs.joinedMessages())
                    .contains(
                            "event=network_risk_assessment_started",
                            "event=network_risk_assessment_completed",
                            "traceId=trace-assessment",
                            "decision=ALLOW")
                    .doesNotContain("I".repeat(43), "C".repeat(43));
        }
    }

    @Test
    void classifiesConcurrentPreAuthExpiryWithoutLoggingExceptionMessage() {
        PreAuthRequiredException failure = new PreAuthRequiredException();
        NetworkRiskAssessmentService target =
                (access, observation) -> Mono.error(failure);
        NetworkRiskAssessmentService service =
                proxy(target, NetworkRiskAssessmentService.class);

        try (DebugLogCapture logs =
                        DebugLogCapture.start(NetworkRiskDiagnosticLoggingAspect.class);
                NetworkRiskDiagnosticContext.Scope ignored =
                        NetworkRiskDiagnosticContext.open(
                                "trace-expiry", 2, "ASYNC", "network_risk_prehandle")) {
            StepVerifier.create(service.assess(
                            access(), mock(TrustedNetworkObservation.class)))
                    .expectErrorSatisfies(error -> assertThat(error).isSameAs(failure))
                    .verify();

            assertThat(logs.joinedMessages())
                    .contains(
                            "event=network_risk_assessment_completed",
                            "outcome=preauth_required",
                            "exceptionClass=PreAuthRequiredException")
                    .doesNotContain(failure.getMessage());
        }
    }

    private static PreAuthAccess access() {
        PreAuthState state = mock(PreAuthState.class);
        when(state.scope()).thenReturn(RiskScope.ADMIN);
        when(state.authState()).thenReturn("ANONYMOUS");
        when(state.sessionType()).thenReturn(RiskSessionType.NONE);
        when(state.deviceDigest())
                .thenReturn(HmacIdentifier.fromProtectedValue("D".repeat(43)));
        return new PreAuthAccess(
                HmacIdentifier.fromProtectedValue("A".repeat(43)), state);
    }

    private static <T> T proxy(T target, Class<T> serviceInterface) {
        AspectJProxyFactory factory = new AspectJProxyFactory();
        factory.setTarget(target);
        factory.setInterfaces(serviceInterface);
        factory.setProxyTargetClass(false);
        factory.addAspect(new NetworkRiskDiagnosticLoggingAspect());
        return serviceInterface.cast(factory.getProxy());
    }
}
