package com.example.temperate.service.registration.verification.delivery.util.microsoft;

import com.example.temperate.service.registration.verification.delivery.exception.VerificationDeliveryException;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.Endpoint;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.Operation;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * 使用个人 Microsoft 账户的 refresh token 换取并缓存 Microsoft Graph 短期访问令牌。
 *
 * <p>并发过期请求共享同一个刷新 Mono，避免向令牌端点并发提交相同 Secret；若服务端轮换 refresh token，
 * 新值只在当前进程内原子替换，任何令牌都不会进入日志或异常消息。</p>
 */
public final class MicrosoftGraphOAuthTokenUtil
        implements MicrosoftGraphAccessTokenSupplier {

    private static final String PROVIDER = "microsoft_graph";
    private static final long EXPIRY_SKEW_SECONDS = 60L;

    private final MicrosoftGraphApiProperties properties;
    private final Clock clock;
    private final Function<MicrosoftGraphTokenRequest, Mono<MicrosoftGraphTokenResponse>>
            tokenRequester;
    private final AtomicReference<String> activeRefreshToken;
    private final Object refreshMonitor = new Object();
    private volatile TokenSnapshot cachedToken;
    private volatile Mono<TokenSnapshot> refreshInFlight;

    public MicrosoftGraphOAuthTokenUtil(
            WebClient webClient,
            MicrosoftGraphApiProperties properties) {
        this(
                properties,
                Clock.systemUTC(),
                request -> {
                    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                    form.add("client_id", request.clientId());
                    form.add("client_secret", request.clientSecret());
                    form.add("refresh_token", request.refreshToken());
                    form.add("grant_type", "refresh_token");
                    form.add("scope", request.scope());
                    return webClient.post()
                            .uri(properties.tokenUri())
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .body(BodyInserters.fromFormData(form))
                            .exchangeToMono(MicrosoftGraphOAuthTokenUtil::safeResponse);
                });
    }

    MicrosoftGraphOAuthTokenUtil(
            MicrosoftGraphApiProperties properties,
            Clock clock,
            Function<MicrosoftGraphTokenRequest, Mono<MicrosoftGraphTokenResponse>>
                    tokenRequester) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.tokenRequester =
                Objects.requireNonNull(tokenRequester, "tokenRequester must not be null");
        this.activeRefreshToken = new AtomicReference<>(properties.refreshToken());
    }

    @Override
    public Mono<String> accessToken() {
        return Mono.defer(() -> {
            TokenSnapshot snapshot = cachedToken;
            if (isUsable(snapshot)) {
                return Mono.just(snapshot.accessToken());
            }
            return refreshOnce().map(TokenSnapshot::accessToken);
        });
    }

    @Override
    public void invalidate() {
        cachedToken = null;
    }

    private Mono<TokenSnapshot> refreshOnce() {
        synchronized (refreshMonitor) {
            TokenSnapshot snapshot = cachedToken;
            if (isUsable(snapshot)) {
                return Mono.just(snapshot);
            }
            if (refreshInFlight == null) {
                // cache() 让同时订阅的消费者共享一次 HTTP 请求；终止后立即释放引用，后续过期可重新刷新。
                refreshInFlight = requestNewToken()
                        .map(this::cacheToken)
                        .doFinally(signal -> clearRefreshInFlight())
                        .cache();
            }
            return refreshInFlight;
        }
    }

    private Mono<MicrosoftGraphTokenResponse> requestNewToken() {
        MicrosoftGraphTokenRequest request = new MicrosoftGraphTokenRequest(
                properties.clientId(),
                properties.clientSecret(),
                activeRefreshToken.get(),
                properties.scope());
        return Mono.defer(() -> tokenRequester.apply(request))
                .timeout(properties.oauthTimeout())
                .onErrorMap(this::tokenFailure);
    }

    private TokenSnapshot cacheToken(MicrosoftGraphTokenResponse response) {
        if (response == null
                || response.accessToken() == null
                || response.accessToken().isBlank()) {
            throw new VerificationDeliveryException(
                    true,
                    PROVIDER,
                    "microsoft_oauth_empty_access_token",
                    failureMetadata(null, null),
                    null);
        }
        if (response.refreshToken() != null && !response.refreshToken().isBlank()) {
            activeRefreshToken.set(response.refreshToken());
        }
        long usableSeconds = Math.max(1L, response.expiresIn() - EXPIRY_SKEW_SECONDS);
        TokenSnapshot snapshot = new TokenSnapshot(
                response.accessToken(),
                clock.instant().plusSeconds(usableSeconds));
        cachedToken = snapshot;
        return snapshot;
    }

    private void clearRefreshInFlight() {
        synchronized (refreshMonitor) {
            refreshInFlight = null;
        }
    }

    private boolean isUsable(TokenSnapshot snapshot) {
        return snapshot != null && snapshot.expiresAt().isAfter(clock.instant());
    }

    private Throwable tokenFailure(Throwable failure) {
        if (failure instanceof VerificationDeliveryException) {
            return failure;
        }
        if (failure instanceof MicrosoftGraphOAuthResponseException responseException) {
            int status = responseException.httpStatus();
            boolean retryable = status == 408 || status == 429 || status >= 500;
            return new VerificationDeliveryException(
                    retryable,
                    PROVIDER,
                    "microsoft_oauth_http_error",
                    failureMetadata(status, responseException),
                    null);
        }
        if (failure instanceof WebClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            boolean retryable = status == 408
                    || status == 429
                    || responseException.getStatusCode().is5xxServerError();
            return new VerificationDeliveryException(
                    retryable,
                    PROVIDER,
                    "microsoft_oauth_http_error",
                    failureMetadata(status, responseException),
                    null);
        }
        return new VerificationDeliveryException(
                true,
                PROVIDER,
                "microsoft_oauth_request_failed",
                failureMetadata(null, failure),
                failure);
    }

    private static VerificationDeliveryProviderMetadata failureMetadata(
            Integer httpStatus, Throwable failure) {
        String oauthError = oauthError(failure);
        List<Integer> oauthErrorCodes = oauthErrorCodes(failure);
        MicrosoftGraphFailureClassifier.Classification classification =
                MicrosoftGraphFailureClassifier.classifyOAuth(httpStatus, failure);
        return new VerificationDeliveryProviderMetadata(
                httpStatus,
                null,
                "failed",
                false,
                responseHeader(failure, "request-id"),
                failure == null ? null : failure.getClass().getSimpleName(),
                Operation.REFRESH_ACCESS_TOKEN,
                Endpoint.OAUTH_TOKEN,
                classification.failureStage(),
                classification.failureCategory(),
                classification.failureHint(),
                classification.recommendedAction(),
                null,
                true,
                retryAfterSeconds(failure),
                oauthError,
                oauthErrorCodesValue(oauthErrorCodes),
                oauthFailureReason(oauthError, oauthErrorCodes));
    }

    private static String oauthFailureReason(String oauthError, List<Integer> oauthErrorCodes) {
        // 网络超时和连接失败没有 OAuth 机器码，不能把它们误判成微软主动拒绝令牌。
        if (oauthError == null && (oauthErrorCodes == null || oauthErrorCodes.isEmpty())) {
            return null;
        }
        return MicrosoftGraphFailureClassifier.oauthFailureReason(oauthError, oauthErrorCodes);
    }

    private static Mono<MicrosoftGraphTokenResponse> safeResponse(ClientResponse response) {
        int httpStatus = response.statusCode().value();
        HttpHeaders headers = response.headers().asHttpHeaders();
        String requestId = headers.getFirst("request-id");
        Long retryAfterSeconds = numericRetryAfter(
                headers.getFirst(HttpHeaders.RETRY_AFTER));
        if (response.statusCode().is2xxSuccessful()) {
            return response.bodyToMono(MicrosoftGraphTokenResponse.class);
        }
        // 错误响应只绑定稳定机器字段，error_description 与原始正文不会进入对象、异常或日志。
        MicrosoftGraphOAuthErrorPayload empty =
                new MicrosoftGraphOAuthErrorPayload(null, List.of());
        return response.bodyToMono(MicrosoftGraphOAuthErrorPayload.class)
                .defaultIfEmpty(empty)
                .onErrorReturn(empty)
                .flatMap(payload -> Mono.error(new MicrosoftGraphOAuthResponseException(
                        httpStatus,
                        payload.error(),
                        payload.errorCodes(),
                        requestId,
                        retryAfterSeconds)));
    }

    private static Long numericRetryAfter(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Long retryAfterSeconds(Throwable failure) {
        if (failure instanceof MicrosoftGraphOAuthResponseException responseException) {
            return responseException.retryAfterSeconds();
        }
        String value = responseHeader(failure, "Retry-After");
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ignored) {
            // OAuth 响应中的 HTTP 日期或非法退避值不进入日志，重试仍由既有 RabbitMQ 延迟策略控制。
            return null;
        }
    }

    private static String responseHeader(Throwable failure, String headerName) {
        if (failure instanceof MicrosoftGraphOAuthResponseException responseException) {
            return "request-id".equalsIgnoreCase(headerName)
                    ? responseException.requestId()
                    : null;
        }
        if (!(failure instanceof WebClientResponseException responseException)) {
            return null;
        }
        return responseException.getHeaders().getFirst(headerName);
    }

    private static String oauthError(Throwable failure) {
        return failure instanceof MicrosoftGraphOAuthResponseException responseException
                ? responseException.oauthError()
                : null;
    }

    private static List<Integer> oauthErrorCodes(Throwable failure) {
        return failure instanceof MicrosoftGraphOAuthResponseException responseException
                ? responseException.errorCodes()
                : List.of();
    }

    private static String oauthErrorCodesValue(List<Integer> errorCodes) {
        if (errorCodes == null || errorCodes.isEmpty()) {
            return null;
        }
        return errorCodes.stream()
                .map(String::valueOf)
                .collect(Collectors.joining("-"));
    }

    /**
     * 表示一次 OAuth refresh_token 交换所需的敏感请求参数。
     *
     * <p>该对象仅在内存中传给令牌端点适配器，字符串表示固定脱敏，防止测试或调试日志泄漏凭据。</p>
     */
    record MicrosoftGraphTokenRequest(
            String clientId,
            String clientSecret,
            String refreshToken,
            String scope) {

        @Override
        public String toString() {
            return "MicrosoftGraphTokenRequest[credentials=protected]";
        }
    }

    /**
     * 表示 Microsoft OAuth 令牌端点的最小成功响应。
     *
     * <p>只绑定运行时需要的访问令牌、过期秒数和可选轮换令牌，不保留 ID Token 等无关字段。</p>
     */
    record MicrosoftGraphTokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("refresh_token") String refreshToken) {

        @Override
        public String toString() {
            return "MicrosoftGraphTokenResponse[tokens=protected]";
        }
    }

    /**
     * 只反序列化 Microsoft OAuth 的稳定机器字段，明确忽略 error_description 和其他第三方内容。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MicrosoftGraphOAuthErrorPayload(
            String error,
            @JsonProperty("error_codes") List<Integer> errorCodes) {

        private MicrosoftGraphOAuthErrorPayload {
            errorCodes = errorCodes == null ? List.of() : List.copyOf(errorCodes);
        }
    }

    private record TokenSnapshot(String accessToken, Instant expiresAt) {
    }
}
