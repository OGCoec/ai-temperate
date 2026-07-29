package com.example.temperate.service.admin.mailinspection.oauth;

import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResultStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailboxCredential;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.WriteTimeoutException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

/**
 * 通过固定代理使用 WebClient 非阻塞交换逐行 Microsoft OAuth Token，并执行最多三次有限重试。
 *
 * <p>实现不缓存任何账号 Token；错误响应只反序列化机器码和数字码，第三方描述与原始正文不进入
 * 日志、异常或任务结果。</p>
 */
@Component
public final class MicrosoftMailboxOAuthClientImpl
        implements MicrosoftMailboxOAuthClient {

    private static final Duration PERMIT_RECHECK_DELAY = Duration.ofMillis(20);

    private final AdminMailInspectionProperties properties;
    private final OAuthTokenRequester requester;
    private final Function<RetryContext, Duration> retryDelay;
    private final Semaphore concurrencyGate;

    @Autowired
    public MicrosoftMailboxOAuthClientImpl(
            @Qualifier("adminMailInspectionWebClient") WebClient webClient,
            AdminMailInspectionProperties properties) {
        this(
                properties,
                request -> requestToken(webClient, properties, request),
                context -> calculateRetryDelay(properties, context));
    }

    MicrosoftMailboxOAuthClientImpl(
            AdminMailInspectionProperties properties,
            OAuthTokenRequester requester,
            Function<RetryContext, Duration> retryDelay) {
        this.properties = Objects.requireNonNull(properties);
        this.requester = Objects.requireNonNull(requester);
        this.retryDelay = Objects.requireNonNull(retryDelay);
        this.concurrencyGate = new Semaphore(properties.oauth().concurrency(), true);
    }

    @Override
    public Mono<MicrosoftMailboxOAuthOutcome> exchange(
            MailboxCredential credential) {
        Objects.requireNonNull(credential, "credential must not be null");
        AtomicInteger attempts = new AtomicInteger();
        return attempt(credential, attempts)
                .timeout(properties.oauth().credentialTimeout())
                .onErrorResume(TimeoutException.class, ignored -> Mono.just(
                        MicrosoftMailboxOAuthOutcome.failure(
                                MailInspectionResultStatus.OAUTH_NETWORK_EXHAUSTED,
                                "oauth_total_timeout",
                                Math.max(1, attempts.get()),
                                true,
                                true)));
    }

    private Mono<MicrosoftMailboxOAuthOutcome> attempt(
            MailboxCredential credential,
            AtomicInteger attempts) {
        int attemptNumber = attempts.incrementAndGet();
        OAuthTokenRequest request =
                new OAuthTokenRequest(credential.clientId(), credential.refreshToken());
        return withPermit(() -> requester.request(request))
                .flatMap(response -> handleResponse(
                        credential, attempts, attemptNumber, response))
                .onErrorResume(failure -> handleTransportFailure(
                        credential, attempts, attemptNumber, failure));
    }

    private Mono<MicrosoftMailboxOAuthOutcome> handleResponse(
            MailboxCredential credential,
            AtomicInteger attempts,
            int attemptNumber,
            OAuthTokenHttpResponse response) {
        if (response.isSuccessfulStatus()) {
            if (response.accessToken() == null || response.accessToken().isBlank()) {
                return Mono.just(MicrosoftMailboxOAuthOutcome.failure(
                        MailInspectionResultStatus.OAUTH_RESPONSE_INVALID,
                        "oauth_access_token_missing",
                        attemptNumber,
                        false,
                        false));
            }
            return Mono.just(MicrosoftMailboxOAuthOutcome.success(
                    response.accessToken(), attemptNumber));
        }

        MailInspectionResultStatus status = MicrosoftOAuthErrorClassifier.classify(
                response.httpStatus(), response.error(), response.errorCodes());
        boolean retryable = isRetryableHttp(response.httpStatus());
        if (!retryable) {
            return Mono.just(MicrosoftMailboxOAuthOutcome.failure(
                    status,
                    reasonFor(status),
                    attemptNumber,
                    false,
                    false));
        }
        return retryOrExhaust(
                credential,
                attempts,
                attemptNumber,
                status,
                response.retryAfterSeconds());
    }

    private Mono<MicrosoftMailboxOAuthOutcome> handleTransportFailure(
            MailboxCredential credential,
            AtomicInteger attempts,
            int attemptNumber,
            Throwable failure) {
        Throwable unwrapped = Exceptions.unwrap(failure);
        if (!isRetryableTransport(unwrapped)) {
            return Mono.just(MicrosoftMailboxOAuthOutcome.failure(
                    MailInspectionResultStatus.OAUTH_RESPONSE_INVALID,
                    "oauth_request_failed",
                    attemptNumber,
                    false,
                    false));
        }
        return retryOrExhaust(
                credential,
                attempts,
                attemptNumber,
                MailInspectionResultStatus.OAUTH_NETWORK_EXHAUSTED,
                null);
    }

    private Mono<MicrosoftMailboxOAuthOutcome> retryOrExhaust(
            MailboxCredential credential,
            AtomicInteger attempts,
            int attemptNumber,
            MailInspectionResultStatus status,
            Long retryAfterSeconds) {
        if (attemptNumber >= properties.oauth().maxAttempts()) {
            return Mono.just(MicrosoftMailboxOAuthOutcome.failure(
                    status,
                    reasonFor(status),
                    attemptNumber,
                    true,
                    true));
        }
        Duration delay = retryDelay.apply(
                new RetryContext(attemptNumber, retryAfterSeconds));
        return Mono.delay(delay).then(attempt(credential, attempts));
    }

    private <T> Mono<T> withPermit(java.util.function.Supplier<Mono<T>> operation) {
        return Mono.defer(() -> {
            if (!concurrencyGate.tryAcquire()) {
                // 等待并发许可只使用 Reactor 定时器，不阻塞 Netty 事件线程。
                return Mono.delay(PERMIT_RECHECK_DELAY)
                        .then(withPermit(operation));
            }
            return Mono.defer(operation)
                    .doFinally(ignored -> concurrencyGate.release());
        });
    }

    private static boolean isRetryableHttp(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private static boolean isRetryableTransport(Throwable failure) {
        Throwable cursor = failure;
        while (cursor != null) {
            if (cursor instanceof WebClientRequestException
                    || cursor instanceof ConnectException
                    || cursor instanceof UnknownHostException
                    || cursor instanceof SocketException
                    || cursor instanceof TimeoutException
                    || cursor instanceof ReadTimeoutException
                    || cursor instanceof WriteTimeoutException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static String reasonFor(MailInspectionResultStatus status) {
        return status.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static Duration calculateRetryDelay(
            AdminMailInspectionProperties properties,
            RetryContext context) {
        if (context.retryAfterSeconds() != null) {
            long bounded = Math.min(
                    Math.max(0L, context.retryAfterSeconds()),
                    properties.oauth().maxRetryAfter().toSeconds());
            return Duration.ofSeconds(bounded);
        }
        long baseMillis = Math.multiplyExact(
                properties.oauth().initialBackoff().toMillis(),
                1L << Math.max(0, context.attemptNumber() - 1));
        double random = ThreadLocalRandom.current().nextDouble(-1.0D, 1.0D);
        long jitterMillis = Math.round(
                baseMillis * properties.oauth().jitter() * random);
        return Duration.ofMillis(Math.max(0L, baseMillis + jitterMillis));
    }

    private static Mono<OAuthTokenHttpResponse> requestToken(
            WebClient webClient,
            AdminMailInspectionProperties properties,
            OAuthTokenRequest request) {
        return webClient.post()
                .uri(properties.oauth().tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("client_id", request.clientId())
                        .with("grant_type", "refresh_token")
                        .with("refresh_token", request.refreshToken())
                        .with("scope", properties.oauth().scope()))
                .exchangeToMono(MicrosoftMailboxOAuthClientImpl::safeResponse);
    }

    private static Mono<OAuthTokenHttpResponse> safeResponse(
            ClientResponse response) {
        int status = response.statusCode().value();
        Long retryAfter = safeRetryAfterSeconds(
                response.headers().asHttpHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        if (response.statusCode().is2xxSuccessful()) {
            return response.bodyToMono(OAuthSuccessPayload.class)
                    .map(payload -> new OAuthTokenHttpResponse(
                            status,
                            payload.accessToken(),
                            null,
                            List.of(),
                            retryAfter))
                    .switchIfEmpty(Mono.just(new OAuthTokenHttpResponse(
                            status, null, null, List.of(), retryAfter)))
                    .onErrorReturn(new OAuthTokenHttpResponse(
                            status, null, null, List.of(), retryAfter));
        }
        // 错误模型故意没有 description 字段，Jackson 会直接丢弃所有非白名单内容。
        return response.bodyToMono(OAuthErrorPayload.class)
                .map(payload -> new OAuthTokenHttpResponse(
                        status,
                        null,
                        payload.error(),
                        Optional.ofNullable(payload.errorCodes()).orElse(List.of()),
                        retryAfter))
                .switchIfEmpty(Mono.just(OAuthTokenHttpResponse.error(
                        status, null, List.of(), retryAfter)))
                .onErrorReturn(OAuthTokenHttpResponse.error(
                        status, null, List.of(), retryAfter));
    }

    private static Long safeRetryAfterSeconds(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            try {
                Instant retryAt = ZonedDateTime.parse(
                                value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant();
                return Math.max(
                        0L,
                        Duration.between(Instant.now(), retryAt).toSeconds());
            } catch (DateTimeParseException invalidDate) {
                return null;
            }
        }
    }

    /**
     * 描述一次重试间隔所需的安全元数据，不包含请求或响应内容。
     */
    record RetryContext(int attemptNumber, Long retryAfterSeconds) {
    }

    /**
     * OAuth 成功响应只接收 access_token；轮换 refresh token 明确不进入对象模型。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OAuthSuccessPayload(
            @com.fasterxml.jackson.annotation.JsonProperty("access_token")
            String accessToken) {

        @Override
        public String toString() {
            return "OAuthSuccessPayload[token=protected]";
        }
    }

    /**
     * OAuth 错误响应只接收机器码和数字码，禁止接收 error_description。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OAuthErrorPayload(
            String error,
            @com.fasterxml.jackson.annotation.JsonProperty("error_codes")
            List<Integer> errorCodes) {
    }
}
