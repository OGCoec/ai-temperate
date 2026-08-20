package com.example.temperate.service.humanverification.impl;

import com.example.temperate.service.humanverification.HumanVerificationCommand;
import com.example.temperate.service.humanverification.HumanVerificationService;
import com.example.temperate.service.humanverification.HumanVerificationType;
import com.example.temperate.service.humanverification.exception.HumanVerificationUnavailableException;
import com.example.temperate.service.registration.enums.RegistrationDiagnosticCode;
import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.netty.channel.ChannelOption;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import java.net.SocketTimeoutException;
import java.net.URI;
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
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * 使用 WebClient 非阻塞调用 Cloudflare Siteverify，并完成 Turnstile 业务绑定校验。
 *
 * <p>该实现把供应商成功标记与允许域名、预期 action、当前 Flow 的 cdata 和挑战时间同时绑定。供应商明确拒绝或
 * 安全绑定失败使用受控业务拒绝；网络、TLS、超时和不可信响应使用供应商不可用异常。两类失败都只记录脱敏诊断，
 * 不记录 Secret、原始 Token、完整 IP 或供应商原始正文。
 */
@Service
public final class TurnstileHumanVerificationServiceImpl
        implements HumanVerificationService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(TurnstileHumanVerificationServiceImpl.class);
    private static final URI SITE_VERIFY_URI =
            URI.create("https://challenges.cloudflare.com/turnstile/v0/siteverify");
    private static final Duration DEFAULT_MAX_CHALLENGE_AGE = Duration.ofMinutes(5);
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(8);
    private static final String CF_RAY_HEADER = "CF-Ray";
    private static final Set<String> ALLOWED_ACTIONS =
            Set.of("register", "login", "password_reset", "oauth_phone");
    private static final Set<String> KNOWN_PROVIDER_ERROR_CODES = Set.of(
            "missing-input-secret",
            "invalid-input-secret",
            "missing-input-response",
            "invalid-input-response",
            "bad-request",
            "timeout-or-duplicate",
            "internal-error");
    private static final Set<String> PROVIDER_REJECTION_ERROR_CODES = Set.of(
            "missing-input-response",
            "invalid-input-response",
            "timeout-or-duplicate");

    private final WebClient webClient;
    private final URI siteVerifyUri;
    private final String secret;
    private final Set<String> allowedHosts;
    private final Clock clock;
    private final Duration maxChallengeAge;

    @Autowired
    public TurnstileHumanVerificationServiceImpl(
            WebClient.Builder webClientBuilder,
            Clock clock,
            @Value("${app.registration.turnstile.secret-key}") String secret,
            @Value("${app.registration.turnstile.allowed-hosts}") List<String> allowedHosts) {
        this(
                buildWebClient(
                        webClientBuilder,
                        DEFAULT_CONNECT_TIMEOUT,
                        DEFAULT_READ_TIMEOUT),
                SITE_VERIFY_URI,
                secret,
                allowedHosts,
                clock,
                DEFAULT_MAX_CHALLENGE_AGE);
    }

    public TurnstileHumanVerificationServiceImpl(
            WebClient webClient,
            URI siteVerifyUri,
            String secret,
            Collection<String> allowedHosts,
            Clock clock,
            Duration maxChallengeAge) {
        this.webClient = requireConfigured(webClient);
        this.siteVerifyUri = requireValidUri(siteVerifyUri);
        this.secret = requireSecret(secret);
        this.allowedHosts = requireAllowedHosts(allowedHosts);
        this.clock = requireConfigured(clock);
        this.maxChallengeAge = requirePositiveDuration(maxChallengeAge);
    }

    @Override
    public HumanVerificationType type() {
        return HumanVerificationType.TURNSTILE;
    }

    @Override
    public Mono<Void> verify(HumanVerificationCommand command) {
        // 每个订阅代表一次独立供应商校验；未订阅时不会提前生成幂等键或发起网络请求。
        return Mono.deferContextual(contextView -> {
            VerificationAttempt attempt = new VerificationAttempt(
                    System.nanoTime(),
                    contextView.getOrDefault(TRACE_ID_CONTEXT_KEY, traceId()));
            if (command == null) {
                return Mono.error(rejected(
                        RegistrationDiagnosticCode.INPUT_INVALID,
                        null,
                        attempt,
                        "",
                        List.of()));
            }

            String responseToken = command.responseToken();
            String remoteIp = command.canonicalClientIp();
            String challengeHandle = command.challengeId();
            String expectedAction = command.expectedAction();
            if (isBlank(responseToken)
                    || isBlank(remoteIp)
                    || isBlank(challengeHandle)
                    || !ALLOWED_ACTIONS.contains(expectedAction)) {
                return Mono.error(rejected(
                        RegistrationDiagnosticCode.INPUT_INVALID,
                        null,
                        attempt,
                        "",
                        List.of()));
            }

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("secret", secret);
            form.add("response", responseToken);
            form.add("remoteip", remoteIp);
            // 同一订阅只发送一次请求且不自动重试，防止一次性 Token 被供应商重复消费。
            form.add("idempotency_key", UUID.randomUUID().toString());

            return webClient.post()
                    .uri(siteVerifyUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .exchangeToMono(providerResponse -> {
                        String providerRayId = safeRayId(
                                providerResponse.headers().asHttpHeaders()
                                        .getFirst(CF_RAY_HEADER));
                        if (!providerResponse.statusCode().is2xxSuccessful()) {
                            // 非成功响应只释放数据缓冲区，不解析、记录或传播供应商正文。
                            return providerResponse.releaseBody()
                                    .onErrorResume(ignored -> Mono.empty())
                                    .then(Mono.error(new SiteVerifyHttpStatusException(
                                            providerResponse.statusCode().value(),
                                            providerRayId)));
                        }
                        return providerResponse.bodyToMono(TurnstileResponse.class)
                                .onErrorMap(failure -> new SiteVerifyBodyException(
                                        providerRayId,
                                        failure))
                                .switchIfEmpty(Mono.defer(() -> Mono.error(unavailable(
                                        RegistrationDiagnosticCode
                                                .SITEVERIFY_MALFORMED_RESPONSE,
                                        null,
                                        attempt,
                                        providerRayId,
                                        List.of()))))
                                .map(body -> new VerifiedProviderResponse(
                                        body,
                                        providerRayId,
                                        System.nanoTime()));
                    })
                    .onErrorMap(
                            SiteVerifyHttpStatusException.class,
                            exception -> unavailable(
                                    RegistrationDiagnosticCode.SITEVERIFY_HTTP_ERROR,
                                    exception,
                                    attempt,
                                    exception.providerRayId(),
                                    List.of()))
                    .onErrorMap(
                            SiteVerifyBodyException.class,
                            exception -> unavailable(
                                    transportFailureReason(exception.getCause()),
                                    exception.getCause(),
                                    attempt,
                                    exception.providerRayId(),
                                    List.of()))
                    .onErrorMap(
                            failure -> !(failure instanceof RegistrationException)
                                    && !(failure
                                    instanceof HumanVerificationUnavailableException),
                            failure -> unavailable(
                                    transportFailureReason(failure),
                                    failure,
                                    attempt,
                                    "",
                                    List.of()))
                    .flatMap(providerResponse -> {
                        TurnstileResponse body = providerResponse.body();
                        long tokenAgeMillis = validateResponse(
                                body,
                                challengeHandle,
                                expectedAction,
                                attempt,
                                providerResponse.providerRayId());
                        long bindingValidatedNanos = System.nanoTime();
                        LOGGER.info(
                                "turnstile_verification_succeeded traceId={} "
                                        + "providerRequestMs={} bindingValidationMs={} "
                                        + "elapsedMs={} providerRay={} hostname={} action={} "
                                        + "tokenAgeMs={}",
                                attempt.traceId(),
                                elapsedMillis(
                                        attempt.startedNanos(),
                                        providerResponse.providerRespondedNanos()),
                                elapsedMillis(
                                        providerResponse.providerRespondedNanos(),
                                        bindingValidatedNanos),
                                elapsedMillis(attempt.startedNanos()),
                                providerResponse.providerRayId(),
                                normalizeHostname(body.hostname()),
                                body.action(),
                                tokenAgeMillis);
                        return Mono.empty();
                    });
        });
    }

    private long validateResponse(
            TurnstileResponse response,
            String challengeHandle,
            String expectedAction,
            VerificationAttempt attempt,
            String providerRayId) {
        if (response == null) {
            throw unavailable(
                    RegistrationDiagnosticCode.SITEVERIFY_MALFORMED_RESPONSE,
                    null,
                    attempt,
                    providerRayId,
                    List.of());
        }
        if (response.success() == null) {
            throw unavailable(
                    RegistrationDiagnosticCode.SITEVERIFY_MALFORMED_RESPONSE,
                    null,
                    attempt,
                    providerRayId,
                    response.errorCodes());
        }
        if (!response.success()) {
            if (isProviderUnavailable(response.errorCodes())) {
                throw unavailable(
                        providerUnavailableReason(response.errorCodes()),
                        null,
                        attempt,
                        providerRayId,
                        response.errorCodes());
            }
            throw rejected(
                    providerRejectionReason(response.errorCodes()),
                    null,
                    attempt,
                    providerRayId,
                    response.errorCodes());
        }
        // success 只是必要条件；逐项区分绑定失败，以保留防重放边界和既有诊断能力。
        if (!allowedHosts.contains(normalizeHostname(response.hostname()))) {
            throw rejected(
                    RegistrationDiagnosticCode.HOSTNAME_MISMATCH,
                    null,
                    attempt,
                    providerRayId,
                    response.errorCodes());
        }
        if (!expectedAction.equals(response.action())) {
            throw rejected(
                    RegistrationDiagnosticCode.ACTION_MISMATCH,
                    null,
                    attempt,
                    providerRayId,
                    response.errorCodes());
        }
        if (!Objects.equals(challengeHandle, response.cdata())) {
            throw rejected(
                    RegistrationDiagnosticCode.CDATA_MISMATCH,
                    null,
                    attempt,
                    providerRayId,
                    response.errorCodes());
        }
        if (response.challengeTimestamp() == null
                || response.challengeTimestamp().isBlank()) {
            throw unavailable(
                    RegistrationDiagnosticCode.TIMESTAMP_INVALID,
                    null,
                    attempt,
                    providerRayId,
                    response.errorCodes());
        }

        Instant challengeTimestamp;
        try {
            challengeTimestamp = Instant.parse(response.challengeTimestamp());
        } catch (DateTimeParseException exception) {
            throw unavailable(
                    RegistrationDiagnosticCode.TIMESTAMP_INVALID,
                    exception,
                    attempt,
                    providerRayId,
                    response.errorCodes());
        }
        Instant now = clock.instant();
        if (challengeTimestamp.isAfter(now)) {
            throw rejected(
                    RegistrationDiagnosticCode.TIMESTAMP_FUTURE,
                    null,
                    attempt,
                    providerRayId,
                    response.errorCodes());
        }
        if (challengeTimestamp.isBefore(now.minus(maxChallengeAge))) {
            throw rejected(
                    RegistrationDiagnosticCode.TIMESTAMP_EXPIRED,
                    null,
                    attempt,
                    providerRayId,
                    response.errorCodes());
        }
        return Duration.between(challengeTimestamp, now).toMillis();
    }

    private static RegistrationException rejected(
            RegistrationDiagnosticCode diagnosticCode) {
        return new RegistrationException(
                RegistrationErrorCode.TURNSTILE_REJECTED,
                "Turnstile verification was rejected",
                diagnosticCode);
    }

    private static RegistrationException rejected(
            RegistrationDiagnosticCode diagnosticCode,
            Throwable cause,
            VerificationAttempt attempt,
            String providerRayId,
            Collection<String> providerErrorCodes) {
        LOGGER.warn(
                "turnstile_verification_failed traceId={} diagnosticCode={} elapsedMs={} "
                        + "providerRay={} providerErrorCodes={} causeType={}",
                attempt.traceId(),
                diagnosticCode,
                elapsedMillis(attempt.startedNanos()),
                safeRayId(providerRayId),
                safeProviderErrorCodes(providerErrorCodes),
                cause == null ? "none" : cause.getClass().getSimpleName());
        return new RegistrationException(
                RegistrationErrorCode.TURNSTILE_REJECTED,
                "Turnstile verification was rejected",
                diagnosticCode,
                cause);
    }

    private static HumanVerificationUnavailableException unavailable(
            RegistrationDiagnosticCode diagnosticCode,
            Throwable cause,
            VerificationAttempt attempt,
            String providerRayId,
            Collection<String> providerErrorCodes) {
        Throwable diagnosticCause = unwrapRequestCause(cause);
        LOGGER.warn(
                "turnstile_verification_unavailable traceId={} diagnosticCode={} elapsedMs={} "
                        + "providerRay={} providerErrorCodes={} causeType={}",
                attempt.traceId(),
                diagnosticCode,
                elapsedMillis(attempt.startedNanos()),
                safeRayId(providerRayId),
                safeProviderErrorCodes(providerErrorCodes),
                diagnosticCause == null
                        ? "none"
                        : diagnosticCause.getClass().getSimpleName());
        return new HumanVerificationUnavailableException(
                HumanVerificationType.TURNSTILE,
                diagnosticCause);
    }

    static WebClient buildWebClient(
            WebClient.Builder builder,
            Duration connectTimeout,
            Duration readTimeout) {
        if (builder == null) {
            throw rejected(RegistrationDiagnosticCode.CONFIGURATION_INVALID);
        }
        Duration validConnectTimeout = requirePositiveDuration(connectTimeout);
        Duration validReadTimeout = requirePositiveDuration(readTimeout);
        int connectTimeoutMillis;
        try {
            connectTimeoutMillis = Math.toIntExact(validConnectTimeout.toMillis());
        } catch (ArithmeticException exception) {
            throw rejected(RegistrationDiagnosticCode.CONFIGURATION_INVALID);
        }
        if (connectTimeoutMillis <= 0) {
            throw rejected(RegistrationDiagnosticCode.CONFIGURATION_INVALID);
        }

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                .responseTimeout(validReadTimeout);
        // clone 保证本实现的连接器和超时配置不会污染 Spring 注入的共享 Builder。
        return builder.clone()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
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

    private static boolean isProviderUnavailable(
            Collection<String> providerErrorCodes) {
        if (providerErrorCodes == null || providerErrorCodes.isEmpty()) {
            return true;
        }
        List<String> normalizedCodes = providerErrorCodes.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        return normalizedCodes.isEmpty()
                || normalizedCodes.stream()
                .anyMatch(code -> !PROVIDER_REJECTION_ERROR_CODES.contains(code));
    }

    private static RegistrationDiagnosticCode providerUnavailableReason(
            Collection<String> providerErrorCodes) {
        boolean secretConfigurationFailure = providerErrorCodes != null
                && providerErrorCodes.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .anyMatch(code -> code.equals("missing-input-secret")
                        || code.equals("invalid-input-secret"));
        if (secretConfigurationFailure) {
            return RegistrationDiagnosticCode.CONFIGURATION_INVALID;
        }
        return RegistrationDiagnosticCode.SITEVERIFY_TRANSPORT_ERROR;
    }

    private static RegistrationDiagnosticCode transportFailureReason(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (current instanceof WebClientResponseException) {
                return RegistrationDiagnosticCode.SITEVERIFY_HTTP_ERROR;
            }
            if (current instanceof ConnectTimeoutException
                    || current instanceof HttpConnectTimeoutException) {
                return RegistrationDiagnosticCode.SITEVERIFY_CONNECT_TIMEOUT;
            }
            if (current instanceof ReadTimeoutException
                    || current instanceof TimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException) {
                return RegistrationDiagnosticCode.SITEVERIFY_READ_TIMEOUT;
            }
            if (current instanceof DecodingException) {
                return RegistrationDiagnosticCode.SITEVERIFY_MALFORMED_RESPONSE;
            }
            if (current instanceof WebClientRequestException requestException
                    && requestException.getCause() != null) {
                current = requestException.getCause();
                continue;
            }
            current = current.getCause();
        }
        return RegistrationDiagnosticCode.SITEVERIFY_TRANSPORT_ERROR;
    }

    private static Throwable unwrapRequestCause(Throwable failure) {
        if (failure instanceof WebClientRequestException requestException
                && requestException.getCause() != null) {
            return requestException.getCause();
        }
        return failure;
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
        return normalized.matches("^[A-Za-z0-9-]{1,128}$")
                ? normalized
                : "invalid";
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
            @JsonProperty("error-codes") List<String> errorCodes) {
    }

    /**
     * 保存一次订阅的起始时间和入口 traceId，确保切换 Reactor 线程后日志仍归属于原请求。
     */
    private record VerificationAttempt(long startedNanos, String traceId) {
    }

    /**
     * 保存已解析供应商响应及阶段时间，仅在当前响应式链中传递，不缓存敏感请求材料。
     */
    private record VerifiedProviderResponse(
            TurnstileResponse body,
            String providerRayId,
            long providerRespondedNanos) {
    }

    /**
     * 保留 Siteverify 非成功 HTTP 状态和关联 Ray ID，但不读取或传播供应商响应正文。
     */
    private static final class SiteVerifyHttpStatusException extends RuntimeException {

        private final String providerRayId;

        private SiteVerifyHttpStatusException(int statusCode, String providerRayId) {
            super("Turnstile returned HTTP status " + statusCode);
            this.providerRayId = providerRayId;
        }

        private String providerRayId() {
            return providerRayId;
        }
    }

    /**
     * 将 2xx 正文解码失败与该响应的 Ray ID 一起带出交换函数，日志仍不包含供应商原始正文。
     */
    private static final class SiteVerifyBodyException extends RuntimeException {

        private final String providerRayId;

        private SiteVerifyBodyException(
                String providerRayId,
                Throwable cause) {
            super("Turnstile response body could not be decoded", cause);
            this.providerRayId = providerRayId;
        }

        private String providerRayId() {
            return providerRayId;
        }
    }
}
