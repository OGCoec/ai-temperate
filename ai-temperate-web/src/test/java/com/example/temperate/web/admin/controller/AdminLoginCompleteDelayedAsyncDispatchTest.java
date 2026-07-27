package com.example.temperate.web.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.challenge.RiskChallengeService;
import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.decision.NetworkRiskAssessmentService;
import com.example.temperate.service.risk.decision.RiskAssessment;
import com.example.temperate.service.risk.domain.RiskDecision;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.observability.NetworkRiskMetrics;
import com.example.temperate.service.risk.observability.WebRtcMetrics;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthIssue;
import com.example.temperate.service.risk.preauth.domain.PreAuthRequiredException;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.service.risk.webrtc.domain.WebRtcVerificationDecision;
import com.example.temperate.service.risk.webrtc.service.WebRtcVerificationService;
import com.example.temperate.web.risk.NetworkRiskInterceptor;
import com.example.temperate.web.risk.PreAuthTransport;
import com.example.temperate.web.risk.RiskRequestContextResolver;
import com.example.temperate.web.risk.webrtc.WebRtcVerificationInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import reactor.core.publisher.Mono;

/**
 * 使用受控延迟和真实 MockMvc ASYNC 分派验证管理员登录结果不会被第二次安全校验覆盖。
 *
 * <p>异步结果由独立调度线程完成，成功分支先模拟 P1 晋升为 P2；任何重复评估都会收到
 * {@link PreAuthRequiredException}，从而稳定复现修复前的虚假 428。</p>
 */
class AdminLoginCompleteDelayedAsyncDispatchTest {

    private static final String TEST_SESSION_COOKIE =
            "admin_session=test-admin-session; Path=/api/admin; Secure; HttpOnly; SameSite=Strict";

    @Timeout(5)
    @ParameterizedTest(name = "status={0}, delay={1}ms")
    @MethodSource("delayedOutcomes")
    void preservesControllerResultAfterDelayedAsyncCompletion(
            int expectedStatus,
            long delayMillis) throws Exception {
        Fixture fixture = fixture();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "admin-login-delayed-completion");
                    thread.setDaemon(true);
                    return thread;
                });

        try (LogCapture networkLogs = LogCapture.start(NetworkRiskInterceptor.class);
                LogCapture webRtcLogs = LogCapture.start(
                        WebRtcVerificationInterceptor.class)) {
            MvcResult pending = fixture.mockMvc()
                    .perform(post("/api/admin/auth/login/complete")
                            .param("resultStatus", Integer.toString(expectedStatus))
                            .header("X-Client-Platform", "H5")
                            .header(
                                    "X-Device-Installation-Id",
                                    "admin-async-idempotency-device"))
                    .andExpect(request().asyncStarted())
                    .andReturn();

            scheduler.schedule(
                    () -> fixture.controller().complete(expectedStatus),
                    delayMillis,
                    TimeUnit.MILLISECONDS);
            pending.getAsyncResult(3_000L);

            MvcResult completed = fixture.mockMvc()
                    .perform(asyncDispatch(pending))
                    .andExpect(status().is(expectedStatus))
                    .andReturn();

            assertThat(completed.getResponse().getContentAsString())
                    .doesNotContain("PREAUTH_REQUIRED");
            assertThat(networkLogs.joined())
                    .contains("event=network_risk_async_result_reused")
                    .doesNotContain(
                            "reason=preauth_concurrent_expiry",
                            "status=428");
            assertThat(webRtcLogs.joined())
                    .contains("event=webrtc_async_result_reused")
                    .doesNotContain("status=428");

            if (expectedStatus == 200) {
                assertThat(completed.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                        .contains(TEST_SESSION_COOKIE);
            } else {
                assertThat(completed.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                        .isEmpty();
            }
        } finally {
            scheduler.shutdownNow();
        }

        assertThat(fixture.controller().completionCount()).isEqualTo(1);
        assertThat(fixture.controller().completionThreadName())
                .isEqualTo("admin-login-delayed-completion");
        verify(fixture.preAuthService(), times(1)).resolve(any(), any(), any());
        verify(fixture.assessmentService(), times(1))
                .assess(fixture.access(), fixture.observation());
        verify(fixture.webRtcService(), times(1))
                .inspect(fixture.access(), fixture.observation().clientIp());
        verify(fixture.preAuthService(), times(expectedStatus == 200 ? 1 : 0))
                .promoteAuthenticated(
                        fixture.access(),
                        RiskSessionType.ADMIN_SESSION,
                        "test-admin-session-token",
                        fixture.observation().observedAt());
    }

    private static Stream<Arguments> delayedOutcomes() {
        return Stream.of(
                Arguments.of(200, 0L),
                Arguments.of(200, 50L),
                Arguments.of(200, 250L),
                Arguments.of(200, 1_000L),
                Arguments.of(401, 100L),
                Arguments.of(503, 100L));
    }

    private static Fixture fixture() {
        NetworkRiskProperties properties = mock(NetworkRiskProperties.class);
        when(properties.mode()).thenReturn(NetworkRiskMode.ENFORCE);
        when(properties.lookupTimeout()).thenReturn(Duration.ofSeconds(8));
        when(properties.webRtc()).thenReturn(new NetworkRiskProperties.WebRtc(
                Duration.ofSeconds(15),
                List.of(URI.create("stun:stun.cloudflare.com:3478")),
                8,
                ""));

        PreAuthAccess access = mock(PreAuthAccess.class);
        PreAuthService preAuthService = mock(PreAuthService.class);
        when(preAuthService.resolve(any(), any(), any()))
                .thenReturn(Optional.of(access));

        TrustedNetworkObservation observation = new TrustedNetworkObservation(
                "198.51.100.10",
                "US",
                64500L,
                new BigDecimal("41.8781"),
                new BigDecimal("-87.6298"),
                Instant.parse("2026-07-26T16:06:24Z"));
        when(preAuthService.promoteAuthenticated(
                        access,
                        RiskSessionType.ADMIN_SESSION,
                        "test-admin-session-token",
                        observation.observedAt()))
                .thenReturn(new PreAuthIssue(
                        "test-promoted-preauth-token",
                        observation.observedAt().plus(Duration.ofHours(6))));
        RiskRequestContextResolver contextResolver = mock(RiskRequestContextResolver.class);
        when(contextResolver.resolve(any())).thenReturn(Optional.of(observation));

        NetworkRiskAssessmentService assessmentService =
                mock(NetworkRiskAssessmentService.class);
        when(assessmentService.assess(access, observation))
                .thenReturn(
                        Mono.just(new RiskAssessment(
                                RiskDecision.ALLOW,
                                100,
                                false,
                                0,
                                HmacIdentifier.fromProtectedValue("I".repeat(43)),
                                HmacIdentifier.fromProtectedValue("C".repeat(43)))),
                        Mono.error(new PreAuthRequiredException()));
        NetworkRiskInterceptor networkRiskInterceptor = new NetworkRiskInterceptor(
                properties,
                preAuthService,
                assessmentService,
                mock(RiskChallengeService.class),
                contextResolver,
                mock(PreAuthTransport.class),
                new ObjectMapper(),
                new NetworkRiskMetrics(new SimpleMeterRegistry()));

        WebRtcVerificationService webRtcService = mock(WebRtcVerificationService.class);
        when(webRtcService.inspect(access, observation.clientIp()))
                .thenReturn(
                        WebRtcVerificationDecision.verified(
                                List.of(observation.clientIp())),
                        WebRtcVerificationDecision.required());
        WebRtcVerificationInterceptor webRtcInterceptor =
                new WebRtcVerificationInterceptor(
                        properties,
                        webRtcService,
                        contextResolver,
                        new ObjectMapper(),
                        new WebRtcMetrics(new SimpleMeterRegistry()));

        DelayedLoginCompleteController controller = new DelayedLoginCompleteController(
                preAuthService,
                access,
                observation);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .addInterceptors(networkRiskInterceptor, webRtcInterceptor)
                .build();
        return new Fixture(
                mockMvc,
                controller,
                preAuthService,
                assessmentService,
                webRtcService,
                access,
                observation);
    }

    private record Fixture(
            MockMvc mockMvc,
            DelayedLoginCompleteController controller,
            PreAuthService preAuthService,
            NetworkRiskAssessmentService assessmentService,
            WebRtcVerificationService webRtcService,
            PreAuthAccess access,
            TrustedNetworkObservation observation) {
    }

    /**
     * 模拟 hCaptcha WebClient 尚未完成时挂起响应，并在受控线程中产生最终登录结果。
     */
    @Controller
    public static final class DelayedLoginCompleteController {

        private final CompletableFuture<ResponseEntity<Void>> result =
                new CompletableFuture<>();
        private final AtomicInteger completionCount = new AtomicInteger();
        private final AtomicReference<String> completionThreadName =
                new AtomicReference<>();
        private final PreAuthService preAuthService;
        private final PreAuthAccess access;
        private final TrustedNetworkObservation observation;

        private DelayedLoginCompleteController(
                PreAuthService preAuthService,
                PreAuthAccess access,
                TrustedNetworkObservation observation) {
            this.preAuthService = preAuthService;
            this.access = access;
            this.observation = observation;
        }

        @PostMapping("/api/admin/auth/login/complete")
        public Mono<ResponseEntity<Void>> completeLogin(
                @RequestParam("resultStatus") int resultStatus) {
            return Mono.fromFuture(result);
        }

        private void complete(int resultStatus) {
            completionThreadName.set(Thread.currentThread().getName());
            completionCount.incrementAndGet();
            ResponseEntity.BodyBuilder response = ResponseEntity.status(resultStatus);
            if (resultStatus == 200) {
                preAuthService.promoteAuthenticated(
                        access,
                        RiskSessionType.ADMIN_SESSION,
                        "test-admin-session-token",
                        observation.observedAt());
                response.header(HttpHeaders.SET_COOKIE, TEST_SESSION_COOKIE);
            }
            result.complete(response.build());
        }

        private int completionCount() {
            return completionCount.get();
        }

        private String completionThreadName() {
            return completionThreadName.get();
        }
    }

    private static final class LogCapture implements AutoCloseable {

        private final Logger logger;
        private final Level previousLevel;
        private final ListAppender<ILoggingEvent> appender;

        private LogCapture(
                Logger logger,
                Level previousLevel,
                ListAppender<ILoggingEvent> appender) {
            this.logger = logger;
            this.previousLevel = previousLevel;
            this.appender = appender;
        }

        private static LogCapture start(Class<?> type) {
            Logger logger = (Logger) LoggerFactory.getLogger(type);
            Level previousLevel = logger.getLevel();
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.setLevel(Level.DEBUG);
            logger.addAppender(appender);
            return new LogCapture(logger, previousLevel, appender);
        }

        private String joined() {
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }
}
