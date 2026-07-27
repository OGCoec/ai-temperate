package com.example.temperate.web.auth.login.controller;

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

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.challenge.RiskChallengeService;
import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.decision.NetworkRiskAssessmentService;
import com.example.temperate.service.risk.decision.RiskAssessment;
import com.example.temperate.service.risk.domain.RiskDecision;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.observability.NetworkRiskMetrics;
import com.example.temperate.service.risk.observability.WebRtcMetrics;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
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
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import reactor.core.publisher.Mono;

/**
 * 验证普通端 Mono 接口复用同一请求的 ASYNC 安全结果，但不会把结果共享给下一条 HTTP 请求。
 */
class UserAsyncDispatchIdempotencyRegressionTest {

    @Test
    @Timeout(5)
    void reusesRiskAndWebRtcResultsForDelayedAsyncDispatch() throws Exception {
        Fixture fixture = fixture(true);
        ScheduledExecutorService scheduler = scheduler();

        try {
            MvcResult pending = fixture.mockMvc()
                    .perform(post("/api/auth/login/code/turnstile")
                            .header("X-Client-Platform", "H5")
                            .header(
                                    "X-Device-Installation-Id",
                                    "user-async-idempotency-device"))
                    .andExpect(request().asyncStarted())
                    .andReturn();

            scheduler.schedule(
                    () -> fixture.controller().completeNext(202),
                    125L,
                    TimeUnit.MILLISECONDS);
            pending.getAsyncResult(3_000L);

            MvcResult completed = fixture.mockMvc()
                    .perform(asyncDispatch(pending))
                    .andExpect(status().isAccepted())
                    .andReturn();

            assertThat(completed.getResponse().getContentAsString())
                    .doesNotContain("PREAUTH_REQUIRED");
        } finally {
            scheduler.shutdownNow();
        }

        assertThat(fixture.controller().completionCount()).isEqualTo(1);
        verify(fixture.preAuthService(), times(1)).resolve(any(), any(), any());
        verify(fixture.assessmentService(), times(1))
                .assess(fixture.access(), fixture.observation());
        verify(fixture.webRtcService(), times(1))
                .inspect(fixture.access(), fixture.observation().clientIp());
    }

    @Test
    @Timeout(5)
    void evaluatesTwoIndependentHttpRequestsSeparately() throws Exception {
        Fixture fixture = fixture(false);
        ScheduledExecutorService scheduler = scheduler();

        try {
            completeRequest(fixture, scheduler, 25L);
            completeRequest(fixture, scheduler, 25L);
        } finally {
            scheduler.shutdownNow();
        }

        assertThat(fixture.controller().completionCount()).isEqualTo(2);
        verify(fixture.preAuthService(), times(2)).resolve(any(), any(), any());
        verify(fixture.assessmentService(), times(2))
                .assess(fixture.access(), fixture.observation());
        verify(fixture.webRtcService(), times(2))
                .inspect(fixture.access(), fixture.observation().clientIp());
    }

    private static void completeRequest(
            Fixture fixture,
            ScheduledExecutorService scheduler,
            long delayMillis) throws Exception {
        MvcResult pending = fixture.mockMvc()
                .perform(post("/api/auth/login/code/turnstile")
                        .header("X-Client-Platform", "H5")
                        .header(
                                "X-Device-Installation-Id",
                                "user-async-idempotency-device"))
                .andExpect(request().asyncStarted())
                .andReturn();

        scheduler.schedule(
                () -> fixture.controller().completeNext(202),
                delayMillis,
                TimeUnit.MILLISECONDS);
        pending.getAsyncResult(3_000L);
        fixture.mockMvc()
                .perform(asyncDispatch(pending))
                .andExpect(status().isAccepted());
    }

    private static Fixture fixture(boolean failOnDuplicateEvaluation) {
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
        RiskRequestContextResolver contextResolver = mock(RiskRequestContextResolver.class);
        when(contextResolver.resolve(any())).thenReturn(Optional.of(observation));

        RiskAssessment allowed = new RiskAssessment(
                RiskDecision.ALLOW,
                100,
                false,
                0,
                HmacIdentifier.fromProtectedValue("I".repeat(43)),
                HmacIdentifier.fromProtectedValue("C".repeat(43)));
        NetworkRiskAssessmentService assessmentService =
                mock(NetworkRiskAssessmentService.class);
        if (failOnDuplicateEvaluation) {
            when(assessmentService.assess(access, observation))
                    .thenReturn(
                            Mono.just(allowed),
                            Mono.error(new PreAuthRequiredException()));
        } else {
            when(assessmentService.assess(access, observation))
                    .thenReturn(Mono.just(allowed));
        }
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
        if (failOnDuplicateEvaluation) {
            when(webRtcService.inspect(access, observation.clientIp()))
                    .thenReturn(
                            WebRtcVerificationDecision.verified(
                                    List.of(observation.clientIp())),
                            WebRtcVerificationDecision.required());
        } else {
            when(webRtcService.inspect(access, observation.clientIp()))
                    .thenReturn(WebRtcVerificationDecision.verified(
                            List.of(observation.clientIp())));
        }
        WebRtcVerificationInterceptor webRtcInterceptor =
                new WebRtcVerificationInterceptor(
                        properties,
                        webRtcService,
                        contextResolver,
                        new ObjectMapper(),
                        new WebRtcMetrics(new SimpleMeterRegistry()));

        DelayedUserController controller = new DelayedUserController();
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

    private static ScheduledExecutorService scheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "user-turnstile-delayed-completion");
            thread.setDaemon(true);
            return thread;
        });
    }

    private record Fixture(
            MockMvc mockMvc,
            DelayedUserController controller,
            PreAuthService preAuthService,
            NetworkRiskAssessmentService assessmentService,
            WebRtcVerificationService webRtcService,
            PreAuthAccess access,
            TrustedNetworkObservation observation) {
    }

    /**
     * 为每个普通端请求创建独立异步结果，以验证请求属性不能跨请求共享。
     */
    @Controller
    public static final class DelayedUserController {

        private final Queue<CompletableFuture<ResponseEntity<Void>>> pending =
                new ConcurrentLinkedQueue<>();
        private final AtomicInteger completionCount = new AtomicInteger();

        @PostMapping("/api/auth/login/code/turnstile")
        public Mono<ResponseEntity<Void>> verifyTurnstile() {
            CompletableFuture<ResponseEntity<Void>> result = new CompletableFuture<>();
            pending.add(result);
            return Mono.fromFuture(result);
        }

        private void completeNext(int status) {
            CompletableFuture<ResponseEntity<Void>> result = pending.poll();
            if (result == null) {
                throw new IllegalStateException("No pending user request.");
            }
            completionCount.incrementAndGet();
            result.complete(ResponseEntity.status(status).build());
        }

        private int completionCount() {
            return completionCount.get();
        }
    }
}
