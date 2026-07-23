package com.example.temperate.service.registration.verification.delivery.util.microsoft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryResult;
import com.example.temperate.service.registration.verification.delivery.exception.VerificationDeliveryException;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.Endpoint;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureCategory;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureHint;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureStage;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.Operation;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.RecommendedAction;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Hooks;
import reactor.test.StepVerifier;

/**
 * 验证 Microsoft Graph 响应式邮件适配器的最小请求、安全响应映射、超时边界和单次 401 刷新行为。
 *
 * <p>测试只使用内存中的请求函数，不连接 Microsoft；请求模型必须依赖 {@code /me} 自动确定发件人，
 * 且任何诊断对象都不得暴露访问令牌、验证码或完整目标地址。</p>
 */
class MicrosoftGraphApiMailUtilTest {

    private static final Instant NOW = Instant.parse("2026-07-19T10:00:00Z");

    @Test
    void buildsMinimalGraphRequestWithoutFromOrSenderAndMapsAcceptedResponse() throws Exception {
        AtomicReference<MicrosoftGraphApiMailUtil.MicrosoftGraphMailRequest> captured =
                new AtomicReference<>();
        MicrosoftGraphApiMailUtil util = util(
                new RefreshableTokenSupplier(),
                request -> {
                    captured.set(request);
                    return Mono.just(response(202, null, "graph-request-202", null));
                });

        VerificationDeliveryResult result = util.sendVerificationCode(
                        new VerificationDeliveryRequest("alice@example.test", "012345"))
                .block();

        MicrosoftGraphApiMailUtil.MicrosoftGraphMailRequest request = captured.get();
        String json = JsonMapper.builder().build().writeValueAsString(request.requestBody());
        assertThat(json)
                .contains("\"subject\":\"注册验证码\"")
                .contains("\"contentType\":\"Text\"")
                .contains("alice@example.test")
                .contains("012345")
                .contains("\"saveToSentItems\":true")
                .doesNotContain("\"from\"")
                .doesNotContain("\"sender\"")
                .doesNotContain("access-token");
        assertThat(request.toString())
                .doesNotContain("access-token")
                .doesNotContain("alice@example.test")
                .doesNotContain("012345");
        assertThat(result.channel()).isEqualTo(VerificationChannel.EMAIL);
        assertThat(result.provider()).isEqualTo("microsoft_graph");
        assertThat(result.providerMessageId()).isNull();
        assertThat(result.acceptedAt()).isEqualTo(NOW);
        assertThat(result.metadata().httpStatus()).isEqualTo(202);
        assertThat(result.metadata().providerStatus()).isEqualTo("accepted");
        assertThat(result.metadata().providerSuccess()).isTrue();
        assertThat(result.metadata().operation()).isEqualTo(Operation.SEND_MAIL);
        assertThat(result.metadata().endpoint()).isEqualTo(Endpoint.ME_SEND_MAIL);
        assertThat(result.metadata().explicitFrom()).isFalse();
        assertThat(result.metadata().authRefreshAttempted()).isFalse();
    }

    @Test
    void unauthorizedInvalidatesAccessTokenAndRetriesOnlyOnce() {
        RefreshableTokenSupplier tokenSupplier = new RefreshableTokenSupplier();
        AtomicInteger calls = new AtomicInteger();
        MicrosoftGraphApiMailUtil util = util(
                tokenSupplier,
                request -> Mono.just(calls.getAndIncrement() == 0
                        ? response(401, "InvalidAuthenticationToken", "request-1", null)
                        : response(202, null, "request-2", null)));

        VerificationDeliveryResult result = util.sendVerificationCode(
                        new VerificationDeliveryRequest("alice@example.test", "012345"))
                .block();

        assertThat(calls).hasValue(2);
        assertThat(tokenSupplier.invalidations).isEqualTo(1);
        assertThat(tokenSupplier.requests).isEqualTo(2);
        assertThat(result.metadata().httpStatus()).isEqualTo(202);
        assertThat(result.metadata().authRefreshAttempted()).isTrue();
    }

    @Test
    void secondUnauthorizedResponseFailsWithoutRefreshingAgain() {
        RefreshableTokenSupplier tokenSupplier = new RefreshableTokenSupplier();
        AtomicInteger calls = new AtomicInteger();
        MicrosoftGraphApiMailUtil util = util(
                tokenSupplier,
                request -> {
                    calls.incrementAndGet();
                    return Mono.just(response(
                            401, "InvalidAuthenticationToken", "request-401", null));
                });

        assertThatThrownBy(() -> util.sendVerificationCode(
                        new VerificationDeliveryRequest("alice@example.test", "012345"))
                .block())
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.retryable()).isFalse();
                    assertThat(exception.metadata().httpStatus()).isEqualTo(401);
                    assertThat(exception.metadata().authRefreshAttempted()).isTrue();
                    assertThat(exception.metadata().failureCategory())
                            .isEqualTo(FailureCategory.AUTHENTICATION_FAILED);
                });
        assertThat(calls).hasValue(2);
        assertThat(tokenSupplier.invalidations).isEqualTo(1);
    }

    @Test
    void classifiesHttpFailuresByRetrySafety() {
        assertRetryable(400, false);
        assertRetryable(403, false);
        assertRetryable(404, false);
        assertRetryable(408, true);
        assertRetryable(429, true);
        assertRetryable(503, true);
    }

    @Test
    void invalidUserUsesAuthenticatedGraphUserDiagnosisAfterRemovingExplicitFrom() {
        MicrosoftGraphApiMailUtil util = util(
                new RefreshableTokenSupplier(),
                request -> Mono.just(response(
                        404, "ErrorInvalidUser", "graph-request-404", null)));

        assertThatThrownBy(() -> util.sendVerificationCode(
                        new VerificationDeliveryRequest("alice@example.test", "012345"))
                .block())
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.retryable()).isFalse();
                    assertThat(exception.safeReason()).isEqualTo("microsoft_graph_http_error");
                    assertThat(exception.metadata().providerCode()).isEqualTo("ErrorInvalidUser");
                    assertThat(exception.metadata().requestId())
                            .isEqualTo("graph-request-404");
                    assertThat(exception.metadata().failureCategory())
                            .isEqualTo(FailureCategory.IDENTITY_RESOLUTION);
                    assertThat(exception.metadata().failureHint())
                            .isEqualTo(FailureHint.GRAPH_RESOURCE_NOT_RESOLVED);
                    assertThat(exception.metadata().recommendedAction())
                            .isEqualTo(RecommendedAction.VERIFY_AUTHENTICATED_GRAPH_USER);
                    assertThat(exception.metadata().explicitFrom()).isFalse();
                    assertThat(exception.metadata().toString())
                            .doesNotContain("alice@example.test")
                            .doesNotContain("012345");
                });
    }

    @Test
    void keepsOnlyBoundedRetryAfterAndSafeGraphIdentifiers() {
        MicrosoftGraphApiMailUtil util = util(
                new RefreshableTokenSupplier(),
                request -> Mono.just(response(
                        429, "TooManyRequests", "graph-request-429", 17L)));

        assertThatThrownBy(() -> util.sendVerificationCode(
                        new VerificationDeliveryRequest("alice@example.test", "012345"))
                .block())
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.metadata().failureCategory())
                            .isEqualTo(FailureCategory.THROTTLED);
                    assertThat(exception.metadata().retryAfterSeconds()).isEqualTo(17L);
                    assertThat(exception.metadata().requestId())
                            .isEqualTo("graph-request-429");
                });
    }

    @Test
    void webClientParsesOnlySafeGraphCodeAndRequestIdFromRawErrorBody() {
        AtomicReference<String> authorization = new AtomicReference<>();
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    authorization.set(request.headers().getFirst(HttpHeaders.AUTHORIZATION));
                    return Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .header("request-id", "graph-request-safe")
                            .body("{\"error\":{\"code\":\"ErrorInvalidUser\","
                                    + "\"message\":\"alice@example.test 012345\","
                                    + "\"details\":[{\"token\":\"secret-token\"}]}}")
                            .build());
                })
                .build();
        MicrosoftGraphApiMailUtil util = new MicrosoftGraphApiMailUtil(
                webClient, properties(), new RefreshableTokenSupplier());

        assertThatThrownBy(() -> util.sendVerificationCode(
                        new VerificationDeliveryRequest("alice@example.test", "012345"))
                .block())
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.metadata().providerCode())
                            .isEqualTo("ErrorInvalidUser");
                    assertThat(exception.metadata().requestId())
                            .isEqualTo("graph-request-safe");
                    assertThat(exception.metadata().toString())
                            .doesNotContain("alice@example.test")
                            .doesNotContain("012345")
                            .doesNotContain("secret-token");
                });
        assertThat(authorization.get()).isEqualTo("Bearer access-token-1");
    }

    @Test
    void sendTimeoutIsClassifiedAsSendMailFailure() {
        MicrosoftGraphApiMailUtil util = new MicrosoftGraphApiMailUtil(
                Duration.ofSeconds(10),
                new RefreshableTokenSupplier(),
                request -> Mono.never(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        List<Throwable> dropped = new CopyOnWriteArrayList<>();
        Hooks.onErrorDropped(dropped::add);
        try {
            StepVerifier.withVirtualTime(() -> util.sendVerificationCode(
                            new VerificationDeliveryRequest("alice@example.test", "012345")))
                    .thenAwait(Duration.ofSeconds(10))
                    .expectErrorSatisfies(failure -> {
                        assertThat(failure).isInstanceOf(VerificationDeliveryException.class);
                        VerificationDeliveryException exception =
                                (VerificationDeliveryException) failure;
                        assertThat(exception.retryable()).isTrue();
                        assertThat(exception.metadata().operation())
                                .isEqualTo(Operation.SEND_MAIL);
                        assertThat(exception.metadata().failureStage())
                                .isEqualTo(FailureStage.TIMEOUT);
                    })
                    .verify();
            assertThat(dropped).isEmpty();
        } finally {
            Hooks.resetOnErrorDropped();
        }
    }

    @Test
    void oauthFailureKeepsRefreshAccessTokenOperation() {
        VerificationDeliveryProviderMetadata metadata =
                new VerificationDeliveryProviderMetadata(
                        null,
                        null,
                        "failed",
                        false,
                        null,
                        "TimeoutException",
                        Operation.REFRESH_ACCESS_TOKEN,
                        Endpoint.OAUTH_TOKEN,
                        FailureStage.TIMEOUT,
                        FailureCategory.TIMEOUT,
                        FailureHint.PROVIDER_REQUEST_TIMED_OUT,
                        RecommendedAction.CHECK_TIMEOUT_AND_RETRY,
                        null,
                        true,
                        null);
        MicrosoftGraphApiMailUtil util = util(
                () -> Mono.error(new VerificationDeliveryException(
                        true,
                        "microsoft_graph",
                        "microsoft_oauth_request_failed",
                        metadata,
                        null)),
                request -> Mono.error(new AssertionError("sendMail must not be called")));

        assertThatThrownBy(() -> util.sendVerificationCode(
                        new VerificationDeliveryRequest("alice@example.test", "012345"))
                .block())
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.safeReason())
                            .isEqualTo("microsoft_oauth_request_failed");
                    assertThat(exception.metadata().operation())
                            .isEqualTo(Operation.REFRESH_ACCESS_TOKEN);
                });
    }

    @Test
    void networkFailureIsRetryableAndDoesNotExposeExceptionMessage() {
        MicrosoftGraphApiMailUtil util = util(
                new RefreshableTokenSupplier(),
                request -> Mono.error(new IllegalStateException(
                        new IOException("contains alice@example.test and 012345"))));

        assertThatThrownBy(() -> util.sendVerificationCode(
                        new VerificationDeliveryRequest("alice@example.test", "012345"))
                .block())
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.metadata().failureStage())
                            .isEqualTo(FailureStage.TRANSPORT);
                    assertThat(exception.metadata().failureCategory())
                            .isEqualTo(FailureCategory.TRANSPORT_FAILURE);
                    assertThat(exception.metadata().toString())
                            .doesNotContain("alice@example.test")
                            .doesNotContain("012345");
                });
    }

    @Test
    void invalidRequestFailsBeforeTokenOrGraphCall() {
        RefreshableTokenSupplier tokenSupplier = new RefreshableTokenSupplier();
        AtomicInteger calls = new AtomicInteger();
        MicrosoftGraphApiMailUtil util = util(
                tokenSupplier,
                request -> {
                    calls.incrementAndGet();
                    return Mono.just(response(202, null, null, null));
                });

        Mono<VerificationDeliveryResult> operation = util.sendVerificationCode(
                new VerificationDeliveryRequest("alice@example.test", "12345"));

        assertThatThrownBy(operation::block)
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.retryable()).isFalse();
                    assertThat(exception.metadata().failureStage())
                            .isEqualTo(FailureStage.REQUEST_VALIDATION);
                    assertThat(exception.metadata().failureCategory())
                            .isEqualTo(FailureCategory.INVALID_REQUEST);
                });
        assertThat(tokenSupplier.requests).isZero();
        assertThat(calls).hasValue(0);
    }

    private static MicrosoftGraphApiMailUtil util(
            MicrosoftGraphAccessTokenSupplier tokenSupplier,
            MicrosoftGraphApiMailUtil.MicrosoftGraphMailRequester requester) {
        return new MicrosoftGraphApiMailUtil(
                Duration.ofSeconds(10),
                tokenSupplier,
                requester,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static void assertRetryable(int status, boolean expected) {
        MicrosoftGraphApiMailUtil util = util(
                new RefreshableTokenSupplier(),
                request -> Mono.just(response(status, null, "graph-request", null)));

        assertThatThrownBy(() -> util.sendVerificationCode(
                        new VerificationDeliveryRequest("alice@example.test", "012345"))
                .block())
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.retryable()).isEqualTo(expected);
                    assertThat(exception.provider()).isEqualTo("microsoft_graph");
                    assertThat(exception.metadata().httpStatus()).isEqualTo(status);
                    assertThat(exception.metadata().providerStatus()).isEqualTo("failed");
                    assertThat(exception.metadata().providerSuccess()).isFalse();
                    assertThat(exception.metadata().explicitFrom()).isFalse();
                });
    }

    private static MicrosoftGraphApiMailUtil.MicrosoftGraphMailResponse response(
            int status,
            String providerCode,
            String requestId,
            Long retryAfterSeconds) {
        return new MicrosoftGraphApiMailUtil.MicrosoftGraphMailResponse(
                status, providerCode, requestId, retryAfterSeconds);
    }

    private static MicrosoftGraphApiProperties properties() {
        return new MicrosoftGraphApiProperties(
                "client-id",
                "client-secret",
                "refresh-token",
                "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                "offline_access https://graph.microsoft.com/Mail.Send",
                "https://graph.microsoft.com/v1.0/me/sendMail",
                Duration.ofSeconds(10),
                Duration.ofSeconds(10));
    }

    private static final class RefreshableTokenSupplier
            implements MicrosoftGraphAccessTokenSupplier {

        private int invalidations;
        private int requests;

        @Override
        public Mono<String> accessToken() {
            requests++;
            return Mono.just("access-token-" + requests);
        }

        @Override
        public void invalidate() {
            invalidations++;
        }
    }
}
