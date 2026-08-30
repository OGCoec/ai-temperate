package com.example.temperate.service.risk.logging.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.registration.verification.delivery.logging.DebugLogCapture;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthState;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcBeginResult;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcFailureReason;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcWriteResult;
import com.example.temperate.service.risk.preauth.store.PreAuthStore;
import com.example.temperate.service.risk.webrtc.domain.WebRtcVerificationDecision;
import com.example.temperate.service.risk.webrtc.service.WebRtcVerificationService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

/**
 * 验证 WebRTC 专属 AOP 关联浏览器探测、Redis 状态迁移和服务结论，同时不输出 IP、密文或摘要。
 */
class WebRtcVerificationDiagnosticLoggingAspectTest {

    private static final String RAW_IPV4 = "203.10.97.121";
    private static final String RAW_ENCRYPTED = "sensitive-encrypted-webrtc-evidence";
    private static final HmacIdentifier TOKEN =
            HmacIdentifier.fromProtectedValue("A".repeat(43));
    private static final HmacIdentifier DEVICE =
            HmacIdentifier.fromProtectedValue("D".repeat(43));
    private static final HmacIdentifier IP =
            HmacIdentifier.fromProtectedValue("I".repeat(43));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void logsPendingPreservedAndZeroCandidateFailureWithOneProbeRun() {
        installCorrelation();
        PreAuthStore storeTarget = mock(PreAuthStore.class);
        when(storeTarget.beginWebRtcVerification(
                        RiskScope.USER,
                        TOKEN,
                        DEVICE,
                        IP,
                        1L,
                        Duration.ofSeconds(15),
                        Duration.ofHours(1)))
                .thenReturn(new PreAuthWebRtcBeginResult(
                        PreAuthWebRtcBeginResult.Status.PENDING_PRESERVED,
                        1L,
                        Instant.parse("2026-08-30T18:27:57.316Z"),
                        990L));
        when(storeTarget.writeWebRtcResult(
                        RiskScope.USER,
                        TOKEN,
                        DEVICE,
                        IP,
                        1L,
                        false,
                        PreAuthWebRtcFailureReason.NO_PUBLIC_CANDIDATE,
                        RAW_ENCRYPTED,
                        false,
                        Duration.ofHours(1)))
                .thenReturn(PreAuthWebRtcWriteResult.UPDATED);
        PreAuthStore store = proxy(storeTarget, PreAuthStore.class);

        WebRtcVerificationService serviceTarget = mock(WebRtcVerificationService.class);
        PreAuthAccess access = access();
        when(serviceTarget.begin(access, RAW_IPV4)).thenAnswer(invocation -> {
            store.beginWebRtcVerification(
                    RiskScope.USER,
                    TOKEN,
                    DEVICE,
                    IP,
                    1L,
                    Duration.ofSeconds(15),
                    Duration.ofHours(1));
            return WebRtcVerificationDecision.pending(
                    1L,
                    Instant.parse("2026-08-30T18:27:57.316Z"),
                    990L);
        });
        when(serviceTarget.report(access, RAW_IPV4, "1", List.of())).thenAnswer(invocation -> {
            store.writeWebRtcResult(
                    RiskScope.USER,
                    TOKEN,
                    DEVICE,
                    IP,
                    1L,
                    false,
                    PreAuthWebRtcFailureReason.NO_PUBLIC_CANDIDATE,
                    RAW_ENCRYPTED,
                    false,
                    Duration.ofHours(1));
            return WebRtcVerificationDecision.failed(
                    1L,
                    PreAuthWebRtcFailureReason.NO_PUBLIC_CANDIDATE,
                    List.of());
        });
        WebRtcVerificationService service = proxy(
                serviceTarget,
                WebRtcVerificationService.class);

        try (DebugLogCapture logs = DebugLogCapture.start(
                WebRtcVerificationDiagnosticLoggingAspect.class)) {
            service.begin(access, RAW_IPV4);
            service.report(access, RAW_IPV4, "1", List.of());

            assertThat(logs.joinedMessages())
                    .contains(
                            "event=webrtc_begin_completed",
                            "event=webrtc_begin_store_completed",
                            "result=PENDING_PRESERVED",
                            "remainingMs=990",
                            "event=webrtc_report_received",
                            "submittedCandidateCount=0",
                            "failureReason=NO_PUBLIC_CANDIDATE",
                            "event=webrtc_result_store_completed",
                            "writeResult=UPDATED",
                            "probeRunId=123e4567-e89b-42d3-a456-426614174002")
                    .doesNotContain(
                            RAW_IPV4,
                            RAW_ENCRYPTED,
                            "A".repeat(43),
                            "D".repeat(43),
                            "I".repeat(43));
        }
    }

    @Test
    void distinguishesStartedPendingPreservedAndFailurePreservedStoreResults() {
        installCorrelation();
        PreAuthStore target = mock(PreAuthStore.class);
        when(target.beginWebRtcVerification(
                        RiskScope.USER,
                        TOKEN,
                        DEVICE,
                        IP,
                        1L,
                        Duration.ofSeconds(15),
                        Duration.ofHours(1)))
                .thenReturn(
                        new PreAuthWebRtcBeginResult(
                                PreAuthWebRtcBeginResult.Status.STARTED,
                                1L,
                                Instant.parse("2026-08-30T18:27:57.316Z"),
                                15_000L),
                        new PreAuthWebRtcBeginResult(
                                PreAuthWebRtcBeginResult.Status.PENDING_PRESERVED,
                                1L,
                                Instant.parse("2026-08-30T18:27:57.316Z"),
                                990L),
                        new PreAuthWebRtcBeginResult(
                                PreAuthWebRtcBeginResult.Status.FAILURE_PRESERVED,
                                1L,
                                null,
                                0L));
        PreAuthStore store = proxy(target, PreAuthStore.class);

        try (DebugLogCapture logs = DebugLogCapture.start(
                WebRtcVerificationDiagnosticLoggingAspect.class)) {
            for (int index = 0; index < 3; index++) {
                store.beginWebRtcVerification(
                        RiskScope.USER,
                        TOKEN,
                        DEVICE,
                        IP,
                        1L,
                        Duration.ofSeconds(15),
                        Duration.ofHours(1));
            }

            assertThat(logs.joinedMessages())
                    .contains(
                            "result=STARTED",
                            "result=PENDING_PRESERVED",
                            "remainingMs=990",
                            "result=FAILURE_PRESERVED");
        }
    }

    @Test
    void distinguishesUpdatedStaleNetworkChangedAndDeadlineExpiredWriteResults() {
        installCorrelation();
        PreAuthStore target = mock(PreAuthStore.class);
        when(target.writeWebRtcResult(
                        RiskScope.USER,
                        TOKEN,
                        DEVICE,
                        IP,
                        1L,
                        false,
                        PreAuthWebRtcFailureReason.NO_PUBLIC_CANDIDATE,
                        null,
                        false,
                        Duration.ofHours(1)))
                .thenReturn(
                        PreAuthWebRtcWriteResult.UPDATED,
                        PreAuthWebRtcWriteResult.STALE_GENERATION,
                        PreAuthWebRtcWriteResult.NETWORK_CHANGED,
                        PreAuthWebRtcWriteResult.DEADLINE_EXPIRED);
        PreAuthStore store = proxy(target, PreAuthStore.class);

        try (DebugLogCapture logs = DebugLogCapture.start(
                WebRtcVerificationDiagnosticLoggingAspect.class)) {
            for (int index = 0; index < 4; index++) {
                store.writeWebRtcResult(
                        RiskScope.USER,
                        TOKEN,
                        DEVICE,
                        IP,
                        1L,
                        false,
                        PreAuthWebRtcFailureReason.NO_PUBLIC_CANDIDATE,
                        null,
                        false,
                        Duration.ofHours(1));
            }

            assertThat(logs.joinedMessages())
                    .contains(
                            "writeResult=UPDATED",
                            "writeResult=STALE_GENERATION",
                            "writeResult=NETWORK_CHANGED",
                            "writeResult=DEADLINE_EXPIRED");
        }
    }

    private static void installCorrelation() {
        MDC.put("traceId", "123e4567-e89b-42d3-a456-426614174000");
        MDC.put("clientRequestId", "123e4567-e89b-42d3-a456-426614174001");
        MDC.put("pageInstanceId", "123e4567-e89b-42d3-a456-426614174003");
        MDC.put("webRtcProbeRunId", "123e4567-e89b-42d3-a456-426614174002");
        MDC.put("authRequestPath", "/api/_edge/webrtc/report");
        MDC.put("authClientPlatform", "H5");
    }

    private static PreAuthAccess access() {
        PreAuthState state = mock(PreAuthState.class);
        when(state.scope()).thenReturn(RiskScope.USER);
        return new PreAuthAccess(TOKEN, state);
    }

    private static <T> T proxy(T target, Class<T> type) {
        AspectJProxyFactory factory = new AspectJProxyFactory();
        factory.setTarget(target);
        factory.setInterfaces(type);
        factory.setProxyTargetClass(false);
        factory.addAspect(new WebRtcVerificationDiagnosticLoggingAspect());
        return type.cast(factory.getProxy());
    }
}
