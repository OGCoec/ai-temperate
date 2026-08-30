package com.example.temperate.service.auth.session.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.service.auth.session.access.AccessSessionService;
import com.example.temperate.service.auth.session.access.dto.SessionAccessCommand;
import com.example.temperate.service.auth.session.access.dto.SessionAccessResult;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.auth.session.authentication.dto.command.SessionBootstrapCommand;
import com.example.temperate.service.auth.session.authentication.dto.result.SessionAuthenticationResult;
import com.example.temperate.service.auth.session.authentication.service.SessionAuthenticationService;
import com.example.temperate.service.registration.verification.delivery.logging.DebugLogCapture;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

/**
 * 验证会话服务 AOP 只记录关联标识、结果分类和耗时，不输出命令中的认证凭据。
 */
class SessionAuthenticationDiagnosticAspectTest {

    @Test
    void logsAccessAndBootstrapBoundariesWithoutCredentialValues() {
        String accessToken = "sensitive-access-token";
        String refreshToken = "sensitive-refresh-token";
        String csrfToken = "sensitive-csrf-token";
        String deviceId = "sensitive-device-id";
        SessionPrincipal principal =
                new SessionPrincipal(10001L, "AAAAAAAAAAE", "User");
        Instant expiresAt = Instant.parse("2026-08-30T14:00:00Z");

        AccessSessionService accessTarget = mock(AccessSessionService.class);
        when(accessTarget.authenticateOrRenew(any(SessionAccessCommand.class)))
                .thenReturn(new SessionAccessResult(principal, false, null, expiresAt));
        SessionAuthenticationService bootstrapTarget =
                mock(SessionAuthenticationService.class);
        when(bootstrapTarget.bootstrap(any(SessionBootstrapCommand.class)))
                .thenReturn(new SessionAuthenticationResult(
                        principal, accessToken, csrfToken, expiresAt));
        AccessSessionService accessService = proxy(accessTarget, AccessSessionService.class);
        SessionAuthenticationService bootstrapService =
                proxy(bootstrapTarget, SessionAuthenticationService.class);

        MDC.put("traceId", "trace-session-aop");
        try (DebugLogCapture logs =
                DebugLogCapture.start(SessionAuthenticationDiagnosticAspect.class)) {
            accessService.authenticateOrRenew(new SessionAccessCommand(
                    accessToken, refreshToken, csrfToken, deviceId));
            bootstrapService.bootstrap(new SessionBootstrapCommand(
                    accessToken, refreshToken, deviceId));

            assertThat(logs.joinedMessages())
                    .contains(
                            "event=session_access_service_completed",
                            "event=session_bootstrap_service_completed",
                            "traceId=trace-session-aop")
                    .doesNotContain(accessToken, refreshToken, csrfToken, deviceId);
        } finally {
            MDC.remove("traceId");
        }
    }

    private static <T> T proxy(T target, Class<T> serviceInterface) {
        AspectJProxyFactory factory = new AspectJProxyFactory();
        factory.setTarget(target);
        factory.setInterfaces(serviceInterface);
        factory.setProxyTargetClass(false);
        factory.addAspect(new SessionAuthenticationDiagnosticAspect());
        return serviceInterface.cast(factory.getProxy());
    }
}
