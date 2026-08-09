package com.example.temperate.service.registration.verification.delivery.util.microsoft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.registration.verification.delivery.exception.VerificationDeliveryException;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.Endpoint;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureCategory;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureHint;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureStage;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.Operation;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.RecommendedAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;

/**
 * 验证 Microsoft OAuth refresh token 交换的缓存、并发合并、令牌轮换和错误分类边界。
 */
class MicrosoftGraphOAuthTokenUtilTest {

    private static final Instant NOW = Instant.parse("2026-07-19T10:00:00Z");

    @Test
    void cachesAccessTokenAndUsesRotatedRefreshTokenAfterExpiry() {
        MutableClock clock = new MutableClock(NOW);
        List<MicrosoftGraphOAuthTokenUtil.MicrosoftGraphTokenRequest> requests =
                new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        MicrosoftGraphOAuthTokenUtil util = new MicrosoftGraphOAuthTokenUtil(
                properties(),
                clock,
                request -> {
                    requests.add(request);
                    int call = calls.getAndIncrement();
                    return Mono.just(call == 0
                            ? new MicrosoftGraphOAuthTokenUtil.MicrosoftGraphTokenResponse(
                                    "access-1", 120L, "refresh-2")
                            : new MicrosoftGraphOAuthTokenUtil.MicrosoftGraphTokenResponse(
                                    "access-2", 120L, null));
                });

        assertThat(util.accessToken().block()).isEqualTo("access-1");
        assertThat(util.accessToken().block()).isEqualTo("access-1");
        clock.advance(Duration.ofSeconds(61));
        assertThat(util.accessToken().block()).isEqualTo("access-2");

        assertThat(calls).hasValue(2);
        assertThat(requests.get(0).refreshToken()).isEqualTo("refresh-1");
        assertThat(requests.get(1).refreshToken()).isEqualTo("refresh-2");
    }

    @Test
    void concurrentSubscribersShareOneTokenRefresh() {
        AtomicInteger calls = new AtomicInteger();
        Sinks.One<MicrosoftGraphOAuthTokenUtil.MicrosoftGraphTokenResponse> sink =
                Sinks.one();
        MicrosoftGraphOAuthTokenUtil util = new MicrosoftGraphOAuthTokenUtil(
                properties(),
                new MutableClock(NOW),
                request -> {
                    calls.incrementAndGet();
                    return sink.asMono();
                });

        CompletableFuture<Tuple2<String, String>> future =
                Mono.zip(util.accessToken(), util.accessToken()).toFuture();

        assertThat(calls).hasValue(1);
        sink.tryEmitValue(new MicrosoftGraphOAuthTokenUtil.MicrosoftGraphTokenResponse(
                "shared-access", 120L, null));
        assertThat(future.join().getT1()).isEqualTo("shared-access");
        assertThat(future.join().getT2()).isEqualTo("shared-access");
    }

    @Test
    void nineSecondOauthResponseCompletesInsideTenSecondBudget() {
        MicrosoftGraphOAuthTokenUtil util = new MicrosoftGraphOAuthTokenUtil(
                properties(),
                new MutableClock(NOW),
                request -> Mono.delay(Duration.ofSeconds(9))
                        .map(ignored -> new MicrosoftGraphOAuthTokenUtil.MicrosoftGraphTokenResponse(
                                "access-within-budget", 120L, null)));

        StepVerifier.withVirtualTime(util::accessToken)
                .thenAwait(Duration.ofSeconds(9))
                .expectNext("access-within-budget")
                .verifyComplete();
    }

    @Test
    void responseBeyondOauthBudgetFailsWithRefreshOperationMetadata() {
        MicrosoftGraphOAuthTokenUtil util = new MicrosoftGraphOAuthTokenUtil(
                properties(),
                new MutableClock(NOW),
                request -> Mono.delay(Duration.ofSeconds(11))
                        .map(ignored -> new MicrosoftGraphOAuthTokenUtil.MicrosoftGraphTokenResponse(
                                "too-late", 120L, null)));

        StepVerifier.withVirtualTime(util::accessToken)
                .thenAwait(Duration.ofSeconds(10))
                .expectErrorSatisfies(failure -> {
                    assertThat(failure).isInstanceOf(VerificationDeliveryException.class);
                    VerificationDeliveryException exception =
                            (VerificationDeliveryException) failure;
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.metadata().operation())
                            .isEqualTo(Operation.REFRESH_ACCESS_TOKEN);
                    assertThat(exception.metadata().failureStage())
                            .isEqualTo(FailureStage.TIMEOUT);
                    assertThat(exception.metadata().oauthFailureReason())
                            .isEqualTo("unavailable");
                })
                .verify();
    }

    @Test
    void failedSingleFlightIsClearedBeforeNextSubscription() {
        AtomicInteger calls = new AtomicInteger();
        MicrosoftGraphOAuthTokenUtil util = new MicrosoftGraphOAuthTokenUtil(
                properties(),
                new MutableClock(NOW),
                request -> calls.getAndIncrement() == 0
                        ? Mono.error(new TimeoutException("first refresh timed out"))
                        : Mono.just(new MicrosoftGraphOAuthTokenUtil.MicrosoftGraphTokenResponse(
                                "fresh-access", 120L, null)));

        assertThatThrownBy(() -> util.accessToken().block())
                .isInstanceOf(VerificationDeliveryException.class);
        assertThat(util.accessToken().block()).isEqualTo("fresh-access");
        assertThat(calls).hasValue(2);
    }

    @Test
    void oauthBadRequestIsNotRetryableAndServerFailureIsRetryable() {
        assertOAuthRetryable(HttpStatus.BAD_REQUEST, false);
        assertOAuthRetryable(HttpStatus.SERVICE_UNAVAILABLE, true);
    }

    @Test
    void oauthBadRequestUsesSafeAuthenticationDiagnosticsWithoutResponseBody() {
        MicrosoftGraphOAuthTokenUtil util = new MicrosoftGraphOAuthTokenUtil(
                properties(),
                new MutableClock(NOW),
                request -> Mono.error(WebClientResponseException.create(
                        HttpStatus.BAD_REQUEST.value(),
                        HttpStatus.BAD_REQUEST.getReasonPhrase(),
                        HttpHeaders.EMPTY,
                        "refresh_token and sender@example.test are invalid"
                                .getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> util.accessToken().block())
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.metadata().operation())
                            .isEqualTo(Operation.REFRESH_ACCESS_TOKEN);
                    assertThat(exception.metadata().endpoint()).isEqualTo(Endpoint.OAUTH_TOKEN);
                    assertThat(exception.metadata().failureStage())
                            .isEqualTo(FailureStage.AUTHENTICATION);
                    assertThat(exception.metadata().failureCategory())
                            .isEqualTo(FailureCategory.AUTHENTICATION_FAILED);
                    assertThat(exception.metadata().failureHint())
                            .isEqualTo(FailureHint.OAUTH_CREDENTIAL_OR_REFRESH_TOKEN_REJECTED);
                    assertThat(exception.metadata().recommendedAction())
                            .isEqualTo(RecommendedAction.REAUTHORIZE_OR_VERIFY_CLIENT_CREDENTIALS);
                    assertThat(exception.metadata().toString())
                            .doesNotContain("refresh_token")
                            .doesNotContain("sender@example.test");
                });
    }

    @Test
    void oauthMachineErrorFieldsReachSafeFailureMetadata() {
        MicrosoftGraphOAuthTokenUtil util = new MicrosoftGraphOAuthTokenUtil(
                properties(),
                new MutableClock(NOW),
                request -> Mono.error(new MicrosoftGraphOAuthResponseException(
                        HttpStatus.BAD_REQUEST.value(),
                        "invalid_grant",
                        List.of(700084),
                        "request-700084",
                        null)));

        assertThatThrownBy(() -> util.accessToken().block())
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.metadata().oauthError())
                            .isEqualTo("invalid_grant");
                    assertThat(exception.metadata().oauthErrorCodes())
                            .isEqualTo("700084");
                    assertThat(exception.metadata().oauthFailureReason())
                            .isEqualTo("spa_refresh_token_expired");
                    assertThat(exception.metadata().requestId())
                            .isEqualTo("request-700084");
                    assertThat(exception.metadata().toString())
                            .doesNotContain("refresh-1")
                            .doesNotContain("client-secret");
                });
    }

    @Test
    void sevenDigitMicrosoftOauthCodeIsPreservedForSafeClassification() {
        MicrosoftGraphOAuthTokenUtil util = new MicrosoftGraphOAuthTokenUtil(
                properties(),
                new MutableClock(NOW),
                request -> Mono.error(new MicrosoftGraphOAuthResponseException(
                        HttpStatus.BAD_REQUEST.value(),
                        "invalid_client",
                        List.of(7000215),
                        "request-7000215",
                        null)));

        assertThatThrownBy(() -> util.accessToken().block())
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.metadata().oauthErrorCodes())
                            .isEqualTo("7000215");
                    assertThat(exception.metadata().oauthFailureReason())
                            .isEqualTo("client_secret_invalid");
                });
    }

    private static void assertOAuthRetryable(HttpStatus status, boolean expected) {
        MicrosoftGraphOAuthTokenUtil util = new MicrosoftGraphOAuthTokenUtil(
                properties(),
                new MutableClock(NOW),
                request -> Mono.error(WebClientResponseException.create(
                        status.value(),
                        status.getReasonPhrase(),
                        HttpHeaders.EMPTY,
                        new byte[0],
                        StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> util.accessToken().block())
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.retryable()).isEqualTo(expected);
                    assertThat(exception.provider()).isEqualTo("microsoft_graph");
                    assertThat(exception.metadata().httpStatus())
                            .isEqualTo(status.value());
                    assertThat(exception.metadata().providerStatus()).isEqualTo("failed");
                    assertThat(exception.metadata().providerSuccess()).isFalse();
                });
    }

    private static MicrosoftGraphApiProperties properties() {
        return new MicrosoftGraphApiProperties(
                "client-id",
                "client-secret",
                "refresh-1",
                "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                "offline_access https://graph.microsoft.com/Mail.Send",
                "https://graph.microsoft.com/v1.0/me/sendMail",
                Duration.ofSeconds(10),
                Duration.ofSeconds(10));
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
