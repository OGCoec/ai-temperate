package com.example.temperate.service.registration.service.turnstile.impl;

import com.example.temperate.service.registration.enums.RegistrationDiagnosticCode;
import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.example.temperate.service.registration.service.turnstile.TurnstileVerificationService;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 调用 Cloudflare Siteverify 的 Turnstile 校验实现。
 *
 * <p>用途：将客户端响应提交至服务端校验端点，并验证成功标记、允许域名、动作、挑战句柄和挑战时间。</p>
 *
 * <p>安全原理：不能只信任客户端或第三方的成功标记，必须将返回值与当前注册流程的 cdata、动作和允许站点绑定；
 * 外部调用或响应校验失败统一映射为拒绝结果，不向客户端泄露供应商或密钥细节。</p>
 */
@Service
public final class TurnstileVerificationServiceImpl implements TurnstileVerificationService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(TurnstileVerificationServiceImpl.class);
    private static final URI SITE_VERIFY_URI =
            URI.create("https://challenges.cloudflare.com/turnstile/v0/siteverify");
    private static final Duration DEFAULT_MAX_CHALLENGE_AGE = Duration.ofMinutes(5);
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(8);
    private static final String CF_RAY_HEADER = "CF-Ray";
    private static final Set<String> KNOWN_PROVIDER_ERROR_CODES = Set.of(
            "missing-input-secret",
            "invalid-input-secret",
            "missing-input-response",
            "invalid-input-response",
            "bad-request",
            "timeout-or-duplicate",
            "internal-error");

    private final RestClient restClient;
    private final URI siteVerifyUri;
    private final String secret;
    private final Set<String> allowedHosts;
    private final Clock clock;
    private final Duration maxChallengeAge;

    @Autowired
    public TurnstileVerificationServiceImpl(
            RestClient.Builder restClientBuilder,
            Clock clock,
            @Value("${app.registration.turnstile.secret-key}") String secret,
            @Value("${app.registration.turnstile.allowed-hosts}") List<String> allowedHosts) {
        this(
                buildRestClient(
                        restClientBuilder,
                        DEFAULT_CONNECT_TIMEOUT,
                        DEFAULT_READ_TIMEOUT),
                SITE_VERIFY_URI,
                secret,
                allowedHosts,
                clock,
                DEFAULT_MAX_CHALLENGE_AGE);
    }

    public TurnstileVerificationServiceImpl(
            RestClient restClient,
            URI siteVerifyUri,
            String secret,
            Collection<String> allowedHosts,
            Clock clock,
            Duration maxChallengeAge) {
        this.restClient = requireConfigured(restClient);
        this.siteVerifyUri = requireValidUri(siteVerifyUri);
        this.secret = requireSecret(secret);
        this.allowedHosts = requireAllowedHosts(allowedHosts);
        this.clock = requireConfigured(clock);
        this.maxChallengeAge = requirePositiveDuration(maxChallengeAge);
    }

    @Override
    public void verify(
            String responseToken,
            String remoteIp,
            String challengeHandle,
            String expectedAction) {
        long startedNanos = System.nanoTime();
        if (isBlank(responseToken)
                || isBlank(remoteIp)
                || isBlank(challengeHandle)
                || expectedAction == null
                || !expectedAction.matches("^[a-z][a-z0-9_-]{1,31}$")) {
            throw rejected(
                    RegistrationDiagnosticCode.INPUT_INVALID,
                    null,
                    startedNanos,
                    "",
                    List.of());
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", secret);
        form.add("response", responseToken);
        form.add("remoteip", remoteIp);
        // 当前一次客户端回调只提交一次并生成一个关联键；在没有持久化同一键前禁止自动网络重试，避免重复消费 Token。
        form.add("idempotency_key", UUID.randomUUID().toString());

        ResponseEntity<TurnstileResponse> responseEntity;
        try {
            responseEntity = restClient.post()
                    .uri(siteVerifyUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .onStatus(
                            status -> !status.is2xxSuccessful(),
                            (request, rejectedResponse) -> {
                                throw new SiteVerifyHttpStatusException(
                                        rejectedResponse.getStatusCode().value(),
                                        safeRayId(rejectedResponse.getHeaders()
                                                .getFirst(CF_RAY_HEADER)));
                            })
                    .toEntity(TurnstileResponse.class);
        } catch (SiteVerifyHttpStatusException exception) {
            throw rejected(
                    RegistrationDiagnosticCode.SITEVERIFY_HTTP_ERROR,
                    exception,
                    startedNanos,
                    exception.providerRayId(),
                    List.of());
        } catch (RestClientException exception) {
            throw rejected(
                    transportFailureReason(exception),
                    exception,
                    startedNanos,
                    "",
                    List.of());
        }

        TurnstileResponse response = responseEntity.getBody();
        long providerRespondedNanos = System.nanoTime();
        String providerRayId = safeRayId(responseEntity.getHeaders().getFirst(CF_RAY_HEADER));
        long tokenAgeMillis = validateResponse(
                response, challengeHandle, expectedAction, startedNanos, providerRayId);
        long bindingValidatedNanos = System.nanoTime();
        LOGGER.info(
                "turnstile_verification_succeeded traceId={} providerRequestMs={} "
                        + "bindingValidationMs={} elapsedMs={} providerRay={} hostname={} "
                        + "action={} tokenAgeMs={}",
                traceId(),
                elapsedMillis(startedNanos, providerRespondedNanos),
                elapsedMillis(providerRespondedNanos, bindingValidatedNanos),
                elapsedMillis(startedNanos),
                providerRayId,
                normalizeHostname(response.hostname()),
                response.action(),
                tokenAgeMillis);
    }

    private long validateResponse(
            TurnstileResponse response,
            String challengeHandle,
            String expectedAction,
            long startedNanos,
            String providerRayId) {
        if (response == null) {
            throw rejected(
                    RegistrationDiagnosticCode.SITEVERIFY_MALFORMED_RESPONSE,
                    null,
                    startedNanos,
                    providerRayId,
                    List.of());
        }
        if (response.success() == null) {
            throw rejected(
                    RegistrationDiagnosticCode.SITEVERIFY_MALFORMED_RESPONSE,
                    null,
                    startedNanos,
                    providerRayId,
                    response.errorCodes());
        }
        if (!response.success()) {
            throw rejected(
                    providerRejectionReason(response.errorCodes()),
                    null,
                    startedNanos,
                    providerRayId,
                    response.errorCodes());
        }
        // success 只是必要条件；逐项分类绑定失败，既保留防重放边界，也让服务端可以定位状态错位来源。
        if (!allowedHosts.contains(normalizeHostname(response.hostname()))) {
            throw rejected(
                    RegistrationDiagnosticCode.HOSTNAME_MISMATCH,
                    null,
                    startedNanos,
                    providerRayId,
                    response.errorCodes());
        }
        if (!expectedAction.equals(response.action())) {
            throw rejected(
                    RegistrationDiagnosticCode.ACTION_MISMATCH,
                    null,
                    startedNanos,
                    providerRayId,
                    response.errorCodes());
        }
        if (!Objects.equals(challengeHandle, response.cdata())) {
            throw rejected(
                    RegistrationDiagnosticCode.CDATA_MISMATCH,
                    null,
                    startedNanos,
                    providerRayId,
                    response.errorCodes());
        }
        if (response.challengeTimestamp() == null || response.challengeTimestamp().isBlank()) {
            throw rejected(
                    RegistrationDiagnosticCode.TIMESTAMP_INVALID,
                    null,
                    startedNanos,
                    providerRayId,
                    response.errorCodes());
        }

        Instant challengeTimestamp;
        try {
            challengeTimestamp = Instant.parse(response.challengeTimestamp());
        } catch (DateTimeParseException exception) {
            throw rejected(
                    RegistrationDiagnosticCode.TIMESTAMP_INVALID,
                    exception,
                    startedNanos,
                    providerRayId,
                    response.errorCodes());
        }
        Instant now = clock.instant();
        if (challengeTimestamp.isAfter(now)) {
            throw rejected(
                    RegistrationDiagnosticCode.TIMESTAMP_FUTURE,
                    null,
                    startedNanos,
                    providerRayId,
                    response.errorCodes());
        }
        if (challengeTimestamp.isBefore(now.minus(maxChallengeAge))) {
            throw rejected(
                    RegistrationDiagnosticCode.TIMESTAMP_EXPIRED,
                    null,
                    startedNanos,
                    providerRayId,
                    response.errorCodes());
        }
        return Duration.between(challengeTimestamp, now).toMillis();
    }

    private static RegistrationException rejected(RegistrationDiagnosticCode diagnosticCode) {
        return new RegistrationException(
                RegistrationErrorCode.TURNSTILE_REJECTED,
                "Turnstile verification was rejected",
                diagnosticCode);
    }

    private static RegistrationException rejected(
            RegistrationDiagnosticCode diagnosticCode,
            Throwable cause,
            long startedNanos,
            String providerRayId,
            Collection<String> providerErrorCodes) {
        LOGGER.warn(
                "turnstile_verification_failed traceId={} diagnosticCode={} elapsedMs={} "
                        + "providerRay={} providerErrorCodes={} causeType={}",
                traceId(),
                diagnosticCode,
                elapsedMillis(startedNanos),
                safeRayId(providerRayId),
                safeProviderErrorCodes(providerErrorCodes),
                cause == null ? "none" : cause.getClass().getSimpleName());
        return new RegistrationException(
                RegistrationErrorCode.TURNSTILE_REJECTED,
                "Turnstile verification was rejected",
                diagnosticCode,
                cause);
    }

    static RestClient buildRestClient(
            RestClient.Builder builder,
            Duration connectTimeout,
            Duration readTimeout) {
        if (builder == null) {
            throw rejected(RegistrationDiagnosticCode.CONFIGURATION_INVALID);
        }
        Duration validConnectTimeout = requirePositiveDuration(connectTimeout);
        Duration validReadTimeout = requirePositiveDuration(readTimeout);
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(validConnectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(validReadTimeout);
        return builder.requestFactory(requestFactory).build();
    }

    private static <T> T requireConfigured(T value) {
        if (value == null) {
            throw rejected(RegistrationDiagnosticCode.CONFIGURATION_INVALID);
        }
        return value;
    }

    private static URI requireValidUri(URI uri) {
        if (uri == null
                || !uri.isAbsolute()
                || uri.getHost() == null
                || !("https".equalsIgnoreCase(uri.getScheme())
                        || "http".equalsIgnoreCase(uri.getScheme()))) {
            throw rejected(RegistrationDiagnosticCode.CONFIGURATION_INVALID);
        }
        return uri;
    }

    private static String requireSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw rejected(RegistrationDiagnosticCode.CONFIGURATION_INVALID);
        }
        return secret;
    }

    private static Set<String> requireAllowedHosts(Collection<String> allowedHosts) {
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            throw rejected(RegistrationDiagnosticCode.CONFIGURATION_INVALID);
        }

        Set<String> normalizedHosts = new LinkedHashSet<>();
        for (String allowedHost : allowedHosts) {
            String normalizedHost = normalizeHostname(allowedHost);
            if (normalizedHost.isEmpty()
                    || normalizedHost.contains("/")
                    || normalizedHost.contains(":")
                    || normalizedHost.contains("*")) {
                throw rejected(RegistrationDiagnosticCode.CONFIGURATION_INVALID);
            }
            normalizedHosts.add(normalizedHost);
        }
        return Set.copyOf(normalizedHosts);
    }

    private static Duration requirePositiveDuration(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw rejected(RegistrationDiagnosticCode.CONFIGURATION_INVALID);
        }
        return duration;
    }

    private static String normalizeHostname(String hostname) {
        return hostname == null ? "" : hostname.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static RegistrationDiagnosticCode providerRejectionReason(
            Collection<String> providerErrorCodes) {
        return providerErrorCodes != null
                        && providerErrorCodes.contains("timeout-or-duplicate")
                ? RegistrationDiagnosticCode.TOKEN_TIMEOUT_OR_DUPLICATE
                : RegistrationDiagnosticCode.CLOUDFLARE_TOKEN_REJECTED;
    }

    private static RegistrationDiagnosticCode transportFailureReason(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (current instanceof HttpConnectTimeoutException) {
                return RegistrationDiagnosticCode.SITEVERIFY_CONNECT_TIMEOUT;
            }
            if (current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException) {
                return RegistrationDiagnosticCode.SITEVERIFY_READ_TIMEOUT;
            }
            if (current instanceof HttpMessageConversionException) {
                return RegistrationDiagnosticCode.SITEVERIFY_MALFORMED_RESPONSE;
            }
            current = current.getCause();
        }
        return RegistrationDiagnosticCode.SITEVERIFY_TRANSPORT_ERROR;
    }

    private static List<String> safeProviderErrorCodes(Collection<String> errorCodes) {
        if (errorCodes == null || errorCodes.isEmpty()) {
            return List.of();
        }
        return errorCodes.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> KNOWN_PROVIDER_ERROR_CODES.contains(value) ? value : "unknown")
                .distinct()
                .limit(8)
                .toList();
    }

    private static String safeRayId(String value) {
        if (value == null) {
            return "absent";
        }
        String normalized = value.trim();
        return normalized.matches("^[A-Za-z0-9-]{1,128}$") ? normalized : "invalid";
    }

    private static String traceId() {
        String value = MDC.get("traceId");
        return value == null || value.isBlank() ? "absent" : value;
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static long elapsedMillis(long startedNanos, long completedNanos) {
        return Math.max(0L, (completedNanos - startedNanos) / 1_000_000L);
    }

    private record TurnstileResponse(
            Boolean success,
            @JsonProperty("challenge_ts") String challengeTimestamp,
            String hostname,
            String action,
            String cdata,
            @JsonProperty("error-codes") List<String> errorCodes) {}

    /**
     * 保留 Siteverify 非成功 HTTP 状态和关联 Ray ID，但不读取或传播供应商响应正文。
     */
    private static final class SiteVerifyHttpStatusException extends RestClientException {

        private final String providerRayId;

        private SiteVerifyHttpStatusException(int statusCode, String providerRayId) {
            super("Turnstile returned HTTP status " + statusCode);
            this.providerRayId = providerRayId;
        }

        private String providerRayId() {
            return providerRayId;
        }
    }
}
