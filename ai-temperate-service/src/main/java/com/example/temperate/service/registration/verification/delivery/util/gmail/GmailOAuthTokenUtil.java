package com.example.temperate.service.registration.verification.delivery.util.gmail;

import com.example.temperate.service.registration.verification.delivery.exception.VerificationDeliveryException;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * 使用 OAuth refresh token 为 Gmail API 换取短期 access token。
 *
 * <p>缓存只保留 access token 和本地过期时间，不缓存验证码或投递目标；401 后调用方可以使缓存失效并重新换取一次。</p>
 */
public final class GmailOAuthTokenUtil implements GmailAccessTokenSupplier {

    private static final String PROVIDER = "gmail";
    private static final long EXPIRY_SKEW_SECONDS = 60L;

    private final WebClient webClient;
    private final GmailApiProperties properties;
    private final Clock clock;
    private volatile TokenSnapshot cachedToken;

    public GmailOAuthTokenUtil(WebClient webClient, GmailApiProperties properties) {
        this(webClient, properties, Clock.systemUTC());
    }

    GmailOAuthTokenUtil(WebClient webClient, GmailApiProperties properties, Clock clock) {
        this.webClient = Objects.requireNonNull(webClient, "webClient must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Mono<String> accessToken() {
        return Mono.defer(() -> {
            TokenSnapshot snapshot = cachedToken;
            if (snapshot != null && snapshot.expiresAt().isAfter(clock.instant())) {
                return Mono.just(snapshot.accessToken());
            }
            return refreshToken();
        });
    }

    @Override
    public void invalidate() {
        cachedToken = null;
    }

    private Mono<String> refreshToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("refresh_token", properties.refreshToken());
        form.add("grant_type", "refresh_token");
        return webClient.post()
                .uri(properties.tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(GmailTokenResponse.class)
                .timeout(properties.requestTimeout())
                .map(this::cache)
                .onErrorMap(this::tokenFailure);
    }

    private String cache(GmailTokenResponse response) {
        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new VerificationDeliveryException(
                    true,
                    PROVIDER,
                    "gmail_oauth_empty_token",
                    failureMetadata(null, null),
                    null);
        }
        long expiresIn = Math.max(1L, response.expiresIn() - EXPIRY_SKEW_SECONDS);
        cachedToken = new TokenSnapshot(
                response.accessToken(), clock.instant().plusSeconds(expiresIn));
        return response.accessToken();
    }

    private Throwable tokenFailure(Throwable failure) {
        if (failure instanceof VerificationDeliveryException) {
            return failure;
        }
        if (failure instanceof WebClientResponseException responseException) {
            boolean retryable = responseException.getStatusCode().is5xxServerError()
                    || responseException.getStatusCode().value() == 429;
            return new VerificationDeliveryException(
                    retryable,
                    PROVIDER,
                    "gmail_oauth_http_error",
                    failureMetadata(responseException.getStatusCode().value(), responseException),
                    responseException);
        }
        return new VerificationDeliveryException(
                true,
                PROVIDER,
                "gmail_oauth_request_failed",
                failureMetadata(null, failure),
                failure);
    }

    private static VerificationDeliveryProviderMetadata failureMetadata(
            Integer httpStatus, Throwable failure) {
        return new VerificationDeliveryProviderMetadata(
                httpStatus,
                null,
                "failed",
                false,
                null,
                failure == null ? null : failure.getClass().getSimpleName());
    }

    public record GmailTokenResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("access_token") String accessToken,
            @com.fasterxml.jackson.annotation.JsonProperty("expires_in") long expiresIn) {
    }

    private record TokenSnapshot(String accessToken, Instant expiresAt) {
    }
}
