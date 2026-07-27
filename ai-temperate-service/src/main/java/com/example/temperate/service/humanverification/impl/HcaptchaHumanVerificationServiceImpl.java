package com.example.temperate.service.humanverification.impl;

import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.config.properties.AdminProperties;
import com.example.temperate.service.humanverification.HumanVerificationCommand;
import com.example.temperate.service.humanverification.HumanVerificationService;
import com.example.temperate.service.humanverification.HumanVerificationType;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.netty.channel.ChannelOption;
import java.net.URI;
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
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * 使用 WebClient 非阻塞调用 hCaptcha Siteverify，并校验站点、域名和挑战时间。
 *
 * <p>一次订阅只发送一次请求且不自动重试；任何连接、协议或业务拒绝都会 Fail Closed。供应商响应正文、Secret、
 * 原始 Token 和完整 IP 均不会进入日志或异常消息。
 */
@Service
public final class HcaptchaHumanVerificationServiceImpl
        implements HumanVerificationService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(HcaptchaHumanVerificationServiceImpl.class);
    private static final URI SITEVERIFY_URI =
            URI.create("https://api.hcaptcha.com/siteverify");
    private static final Pattern SAFE_TRACE_ID =
            Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final Pattern HOSTNAME_PATTERN = Pattern.compile(
            "^(?=.{1,253}$)"
                    + "(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)"
                    + "(?:\\.(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?))*$");
    private static final Set<String> SAFE_ERROR_CODES = Set.of(
            "missing-input-secret",
            "invalid-input-secret",
            "missing-input-response",
            "invalid-input-response",
            "expired-input-response",
            "already-seen-response",
            "bad-request",
            "missing-remoteip",
            "invalid-remoteip",
            "not-using-dummy-passcode",
            "sitekey-secret-mismatch");

    private final WebClient webClient;
    private final String siteKey;
    private final String secretKey;
    private final Set<String> allowedHosts;
    private final Duration maxChallengeAge;
    private final Duration responseTimeout;
    private final Clock clock;

    @Autowired
    public HcaptchaHumanVerificationServiceImpl(
            WebClient.Builder builder,
            AdminProperties properties,
            Clock clock) {
        AdminProperties.Hcaptcha hcaptcha =
                Objects.requireNonNull(properties, "properties must not be null").hcaptcha();
        this.webClient = buildWebClient(
                builder, hcaptcha.connectTimeout(), hcaptcha.responseTimeout());
        this.siteKey = requireText(hcaptcha.siteKey(), "hCaptcha site key");
        this.secretKey = requireText(hcaptcha.secretKey(), "hCaptcha secret key");
        this.allowedHosts = normalizeHosts(hcaptcha.allowedHosts());
        this.maxChallengeAge = requirePositive(hcaptcha.maxChallengeAge());
        this.responseTimeout = requirePositive(hcaptcha.responseTimeout());
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    HcaptchaHumanVerificationServiceImpl(
            WebClient webClient,
            String siteKey,
            String secretKey,
            Collection<String> allowedHosts,
            Duration maxChallengeAge,
            Clock clock) {
        this(
                webClient,
                siteKey,
                secretKey,
                allowedHosts,
                maxChallengeAge,
                Duration.ofSeconds(8),
                clock);
    }

    HcaptchaHumanVerificationServiceImpl(
            WebClient webClient,
            String siteKey,
            String secretKey,
            Collection<String> allowedHosts,
            Duration maxChallengeAge,
            Duration responseTimeout,
            Clock clock) {
        this.webClient = Objects.requireNonNull(webClient);
        this.siteKey = requireText(siteKey, "hCaptcha site key");
        this.secretKey = requireText(secretKey, "hCaptcha secret key");
        this.allowedHosts = normalizeHosts(allowedHosts);
        this.maxChallengeAge = requirePositive(maxChallengeAge);
        this.responseTimeout = requirePositive(responseTimeout);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public HumanVerificationType type() {
        return HumanVerificationType.HCAPTCHA;
    }

    @Override
    public Mono<Void> verify(HumanVerificationCommand command) {
        // deferContextual 保证未订阅时不创建表单、不启动计时，也不发起任何网络请求。
        return Mono.deferContextual(context -> {
            long startedNanos = System.nanoTime();
            String traceId = sanitizeTraceId(
                    context.getOrDefault(TRACE_ID_CONTEXT_KEY, traceId()));
            validateCommand(command, traceId, startedNanos);

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("secret", secretKey);
            form.add("response", command.responseToken());
            form.add("remoteip", command.canonicalClientIp());
            form.add("sitekey", siteKey);

            return webClient.post()
                    .uri(SITEVERIFY_URI)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .exchangeToMono(response -> {
                        if (!response.statusCode().is2xxSuccessful()) {
                            // 非 2xx 正文可能包含供应商内部信息，只释放缓冲区而不解析或传播。
                            return response.releaseBody()
                                    .onErrorResume(ignored -> Mono.empty())
                                    .then(Mono.error(unavailable(
                                            traceId,
                                            startedNanos,
                                            "provider_http_status",
                                            null)));
                        }
                        return response.bodyToMono(HcaptchaResponse.class)
                                .onErrorMap(
                                        DecodingException.class,
                                        failure -> unavailable(
                                                traceId,
                                                startedNanos,
                                                "provider_response_malformed",
                                                failure))
                                .switchIfEmpty(Mono.defer(() -> Mono.error(unavailable(
                                        traceId,
                                        startedNanos,
                                        "provider_empty_response",
                                        null))));
                    })
                    .onErrorMap(
                            failure -> !(failure instanceof AdminException),
                            failure -> unavailable(
                                    traceId,
                                    startedNanos,
                                    classifyTransportFailure(failure),
                                    failure))
                    .flatMap(body -> validateResponse(body, traceId, startedNanos))
                    // Netty 响应超时约束网络读取，Reactor 总超时同时覆盖解码与迟滞正文。
                    .timeout(responseTimeout)
                    .onErrorMap(
                            failure -> !(failure instanceof AdminException),
                            failure -> unavailable(
                                    traceId,
                                    startedNanos,
                                    "provider_timeout",
                                    failure));
        });
    }

    private Mono<Void> validateResponse(
            HcaptchaResponse response,
            String traceId,
            long startedNanos) {
        if (response == null || response.success() == null) {
            return Mono.error(unavailable(
                    traceId,
                    startedNanos,
                    "provider_response_malformed",
                    null));
        }
        if (!response.success()) {
            LOGGER.warn(
                    "event=admin_hcaptcha_decision traceId={} outcome=rejected "
                            + "failureStage=provider safeReason=provider_rejected "
                            + "durationMs={} providerCodes={}",
                    traceId,
                    elapsedMillis(startedNanos),
                    safeCodes(response.errorCodes()));
            return Mono.error(new AdminException(
                    AdminErrorCode.HCAPTCHA_REJECTED,
                    "Human verification was rejected."));
        }

        // 请求表单已经携带公开 Site Key；成功响应还必须绑定允许域名和当前时间窗，避免跨站 Token 被接受。
        String hostname = normalizeHost(response.hostname());
        if (!isValidHostname(hostname)) {
            LOGGER.warn(
                    "event=admin_hcaptcha_decision traceId={} outcome=rejected "
                            + "failureStage=hostname_binding safeReason=hostname_invalid "
                            + "durationMs={} providerHostname={} configuredHosts={} matchMode=exact",
                    traceId,
                    elapsedMillis(startedNanos),
                    safeProviderHostname(hostname),
                    safeConfiguredHosts());
            return Mono.error(rejected(null));
        }
        if (!allowedHosts.contains(hostname)) {
            LOGGER.warn(
                    "event=admin_hcaptcha_decision traceId={} outcome=rejected "
                            + "failureStage=hostname_binding safeReason=hostname_mismatch "
                            + "durationMs={} providerHostname={} configuredHosts={} matchMode=exact",
                    traceId,
                    elapsedMillis(startedNanos),
                    safeProviderHostname(hostname),
                    safeConfiguredHosts());
            return Mono.error(rejected(null));
        }
        Instant challengeAt;
        try {
            challengeAt = Instant.parse(response.challengeTimestamp());
        } catch (DateTimeParseException | NullPointerException exception) {
            logChallengeTimeRejection(
                    traceId,
                    startedNanos,
                    "challenge_timestamp_invalid",
                    null);
            return Mono.error(rejected(exception));
        }
        Instant now = clock.instant();
        Long challengeAgeMs = durationMillis(challengeAt, now);
        Long maxChallengeAgeMs = durationMillis(maxChallengeAge);
        if (challengeAgeMs == null || maxChallengeAgeMs == null) {
            logChallengeTimeRejection(
                    traceId,
                    startedNanos,
                    "challenge_timestamp_invalid",
                    null);
            return Mono.error(rejected(null));
        }
        if (challengeAgeMs < 0L) {
            logChallengeTimeRejection(
                    traceId,
                    startedNanos,
                    "challenge_from_future",
                    challengeAgeMs);
            return Mono.error(rejected(null));
        }
        if (challengeAgeMs > maxChallengeAgeMs) {
            logChallengeTimeRejection(
                    traceId,
                    startedNanos,
                    "challenge_expired",
                    challengeAgeMs);
            return Mono.error(rejected(null));
        }
        LOGGER.info(
                "event=admin_hcaptcha_decision traceId={} outcome=succeeded "
                        + "failureStage=none safeReason=accepted durationMs={} "
                        + "providerHostname={} challengeAgeMs={}",
                traceId,
                elapsedMillis(startedNanos),
                safeProviderHostname(hostname),
                challengeAgeMs);
        return Mono.empty();
    }

    static WebClient buildWebClient(
            WebClient.Builder builder,
            Duration connectTimeout,
            Duration responseTimeout) {
        Objects.requireNonNull(builder, "WebClient builder must not be null");
        int connectMillis = Math.toIntExact(requirePositive(connectTimeout).toMillis());
        if (connectMillis <= 0) {
            throw new IllegalArgumentException("hCaptcha connect timeout must be positive.");
        }
        HttpClient client = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectMillis)
                .responseTimeout(requirePositive(responseTimeout));
        // clone 避免本实现的连接器和超时配置污染共享 WebClient.Builder。
        return builder.clone()
                .clientConnector(new ReactorClientHttpConnector(client))
                .build();
    }

    private static void validateCommand(
            HumanVerificationCommand command,
            String traceId,
            long startedNanos) {
        boolean responsePresent = command != null
                && command.responseToken() != null
                && !command.responseToken().isBlank();
        boolean responseLengthValid = responsePresent
                && command.responseToken().length() <= 4096;
        boolean clientIpPresent = command != null
                && command.canonicalClientIp() != null
                && !command.canonicalClientIp().isBlank();
        boolean challengePresent = command != null
                && command.challengeId() != null
                && !command.challengeId().isBlank();
        boolean actionValid = command != null
                && command.expectedAction() != null
                && command.expectedAction().isEmpty();
        if (!responsePresent
                || !responseLengthValid
                || !clientIpPresent
                || !challengePresent
                || !actionValid) {
            LOGGER.warn(
                    "event=admin_hcaptcha_decision traceId={} outcome=rejected "
                            + "failureStage=command safeReason=invalid_command durationMs={} "
                            + "responsePresent={} responseLengthValid={} clientIpPresent={} "
                            + "challengePresent={} actionValid={}",
                    traceId,
                    elapsedMillis(startedNanos),
                    responsePresent,
                    responseLengthValid,
                    clientIpPresent,
                    challengePresent,
                    actionValid);
            throw rejected(null);
        }
    }

    private static Set<String> normalizeHosts(Collection<String> hosts) {
        if (hosts == null || hosts.isEmpty()) {
            throw new IllegalArgumentException("hCaptcha allowed hosts are required.");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String host : hosts) {
            String value = normalizeHost(host);
            if (value.isEmpty()
                    || value.contains("/")
                    || value.contains(":")
                    || value.contains("*")) {
                throw new IllegalArgumentException("hCaptcha allowed host is invalid.");
            }
            normalized.add(value);
        }
        return Set.copyOf(normalized);
    }

    private static String normalizeHost(String host) {
        return host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isValidHostname(String host) {
        return host != null && HOSTNAME_PATTERN.matcher(host).matches();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required.");
        }
        return value;
    }

    private static Duration requirePositive(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("Duration must be positive.");
        }
        return value;
    }

    private static AdminException unavailable(
            String traceId,
            long startedNanos,
            String safeReason,
            Throwable cause) {
        LOGGER.warn(
                "event=admin_hcaptcha_decision traceId={} outcome=unavailable "
                        + "failureStage=transport safeReason={} durationMs={}",
                traceId,
                safeReason,
                elapsedMillis(startedNanos));
        return new AdminException(
                AdminErrorCode.HCAPTCHA_UNAVAILABLE,
                "Human verification is temporarily unavailable.",
                cause == null ? new IllegalStateException(safeReason) : cause);
    }

    private static AdminException rejected(Throwable cause) {
        return new AdminException(
                AdminErrorCode.HCAPTCHA_REJECTED,
                "Human verification was rejected.",
                cause);
    }

    private static List<String> safeCodes(Collection<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .map(value -> SAFE_ERROR_CODES.contains(value) ? value : "unknown")
                .distinct()
                .limit(8)
                .toList();
    }

    private static String traceId() {
        String value = MDC.get("traceId");
        return value == null || value.isBlank() ? "absent" : value;
    }

    private static String sanitizeTraceId(String value) {
        return value != null && SAFE_TRACE_ID.matcher(value).matches()
                ? value
                : "absent";
    }

    private List<String> safeConfiguredHosts() {
        return allowedHosts.stream()
                .filter(HcaptchaHumanVerificationServiceImpl::isValidHostname)
                .sorted()
                .limit(8)
                .toList();
    }

    private static String safeProviderHostname(String hostname) {
        return isValidHostname(hostname) ? hostname : "unknown";
    }

    private void logChallengeTimeRejection(
            String traceId,
            long startedNanos,
            String safeReason,
            Long challengeAgeMs) {
        LOGGER.warn(
                "event=admin_hcaptcha_decision traceId={} outcome=rejected "
                        + "failureStage=challenge_time safeReason={} durationMs={} "
                        + "challengeAgeMs={} maxChallengeAgeMs={}",
                traceId,
                safeReason,
                elapsedMillis(startedNanos),
                challengeAgeMs == null ? "unavailable" : challengeAgeMs,
                durationMillis(maxChallengeAge));
    }

    private static String classifyTransportFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current.getClass().getSimpleName().toLowerCase(Locale.ROOT)
                    .contains("timeout")) {
                return "provider_timeout";
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return "provider_request_failed";
    }

    private static Long durationMillis(Instant from, Instant to) {
        try {
            return Duration.between(from, to).toMillis();
        } catch (ArithmeticException exception) {
            return null;
        }
    }

    private static Long durationMillis(Duration duration) {
        try {
            return duration.toMillis();
        } catch (ArithmeticException exception) {
            return null;
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private record HcaptchaResponse(
            Boolean success,
            @JsonProperty("challenge_ts") String challengeTimestamp,
            String hostname,
            @JsonProperty("error-codes") List<String> errorCodes) {
    }
}
