package com.example.temperate.service.humanverification.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.humanverification.HumanVerificationCommand;
import com.example.temperate.service.humanverification.HumanVerificationType;
import com.example.temperate.service.registration.verification.delivery.logging.DebugLogCapture;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * 验证 hCaptcha Service 的冷 Mono、严格域名与时间校验以及 Fail Closed 错误边界。
 */
class HcaptchaHumanVerificationServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void exposesStableHcaptchaType() {
        assertThat(provider(responseClient(
                        200,
                        success("admin.niko000o.site", NOW.minusSeconds(30))))
                        .type())
                .isEqualTo(HumanVerificationType.HCAPTCHA);
    }

    @Test
    void remainsLazyAndSendsExactlyOnceAfterSubscription() {
        AtomicInteger requests = new AtomicInteger();
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    requests.incrementAndGet();
                    return Mono.just(json(200, success("admin.niko000o.site", NOW.minusSeconds(30))));
                })
                .build();
        Mono<Void> result = provider(client).verify(command());

        assertThat(requests).hasValue(0);
        StepVerifier.create(result).verifyComplete();
        assertThat(requests).hasValue(1);
    }

    @Test
    void rejectsMissingOrUnexpectedHostname() {
        for (String hostname : new String[] {"", "not-provided", "attacker.example"}) {
            StepVerifier.create(provider(responseClient(200, success(hostname, NOW.minusSeconds(30))))
                            .verify(command()))
                    .expectErrorSatisfies(error -> assertCode(error, AdminErrorCode.HCAPTCHA_REJECTED))
                    .verify();
        }
    }

    @Test
    void rejectsExpiredChallengeAndBusinessFailure() {
        StepVerifier.create(provider(responseClient(
                                200,
                                success("admin.niko000o.site", NOW.minus(Duration.ofMinutes(3)))))
                        .verify(command()))
                .expectErrorSatisfies(error -> assertCode(error, AdminErrorCode.HCAPTCHA_REJECTED))
                .verify();

        StepVerifier.create(provider(responseClient(
                                200,
                                """
                                {"success":false,"error-codes":["invalid-input-response"]}
                                """))
                        .verify(command()))
                .expectErrorSatisfies(error -> assertCode(error, AdminErrorCode.HCAPTCHA_REJECTED))
                .verify();
    }

    @Test
    void logsSanitizedHostnameMismatchWithoutCredentials() {
        WebClient client = responseClient(
                200,
                success("admin.niko000o.site", NOW.minusSeconds(30)));
        HcaptchaHumanVerificationServiceImpl service = provider(
                client,
                Set.of("niko000o.site"));

        try (DebugLogCapture logs =
                DebugLogCapture.start(HcaptchaHumanVerificationServiceImpl.class)) {
            StepVerifier.create(service.verify(command())
                            .contextWrite(context -> context.put(
                                    "ait.human-verification.traceId",
                                    "trace-hostname")))
                    .expectErrorSatisfies(error ->
                            assertCode(error, AdminErrorCode.HCAPTCHA_REJECTED))
                    .verify();

            assertThat(logs.joinedMessages())
                    .contains("event=admin_hcaptcha_decision")
                    .contains("traceId=trace-hostname")
                    .contains("outcome=rejected")
                    .contains("failureStage=hostname_binding")
                    .contains("safeReason=hostname_mismatch")
                    .contains("providerHostname=admin.niko000o.site")
                    .contains("configuredHosts=[niko000o.site]")
                    .contains("matchMode=exact")
                    .doesNotContain("one-time-hcaptcha-token")
                    .doesNotContain("server-secret")
                    .doesNotContain("public-site-key")
                    .doesNotContain("challenge-id")
                    .doesNotContain("203.0.113.10");
        }
    }

    @Test
    void rejectsUnsafeHostnameWithoutAllowingLogInjection() {
        WebClient client = responseClient(
                200,
                success("admin.niko000o.site\\nforged-entry", NOW.minusSeconds(30)));

        try (DebugLogCapture logs =
                DebugLogCapture.start(HcaptchaHumanVerificationServiceImpl.class)) {
            StepVerifier.create(provider(client).verify(command()))
                    .expectErrorSatisfies(error ->
                            assertCode(error, AdminErrorCode.HCAPTCHA_REJECTED))
                    .verify();

            assertThat(logs.joinedMessages())
                    .contains("failureStage=hostname_binding")
                    .contains("safeReason=hostname_invalid")
                    .contains("providerHostname=unknown")
                    .doesNotContain("forged-entry");
        }
    }

    @Test
    void logsStableChallengeTimeReasons() {
        assertChallengeTimeReason(
                """
                {
                  "success": true,
                  "challenge_ts": "not-an-instant",
                  "hostname": "admin.niko000o.site"
                }
                """,
                "challenge_timestamp_invalid");
        assertChallengeTimeReason(
                success("admin.niko000o.site", NOW.plusSeconds(1)),
                "challenge_from_future");
        assertChallengeTimeReason(
                success("admin.niko000o.site", NOW.minus(Duration.ofMinutes(3))),
                "challenge_expired");
    }

    @Test
    void logsOnlyAllowlistedProviderCodes() {
        String body = """
                {
                  "success": false,
                  "error-codes": [
                    "expired-input-response",
                    "already-seen-response",
                    "missing-remoteip",
                    "invalid-remoteip",
                    "unsafe\\nprovider-value"
                  ]
                }
                """;

        try (DebugLogCapture logs =
                DebugLogCapture.start(HcaptchaHumanVerificationServiceImpl.class)) {
            StepVerifier.create(provider(responseClient(200, body)).verify(command()))
                    .expectErrorSatisfies(error ->
                            assertCode(error, AdminErrorCode.HCAPTCHA_REJECTED))
                    .verify();

            assertThat(logs.joinedMessages())
                    .contains("failureStage=provider")
                    .contains("safeReason=provider_rejected")
                    .contains("providerCodes=[expired-input-response, already-seen-response, "
                            + "missing-remoteip, invalid-remoteip, unknown]")
                    .doesNotContain("unsafe")
                    .doesNotContain("one-time-hcaptcha-token")
                    .doesNotContain("203.0.113.10");
        }
    }

    @Test
    void logsStableTransportReasonsWithoutRawFailureMessage() {
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> Mono.error(
                        new IllegalStateException("raw failure one-time-hcaptcha-token")))
                .build();

        try (DebugLogCapture logs =
                DebugLogCapture.start(HcaptchaHumanVerificationServiceImpl.class)) {
            StepVerifier.create(provider(client).verify(command()))
                    .expectErrorSatisfies(error ->
                            assertCode(error, AdminErrorCode.HCAPTCHA_UNAVAILABLE))
                    .verify();

            assertThat(logs.joinedMessages())
                    .contains("outcome=unavailable")
                    .contains("failureStage=transport")
                    .contains("safeReason=provider_request_failed")
                    .doesNotContain("raw failure")
                    .doesNotContain("one-time-hcaptcha-token");
        }
    }

    @Test
    void invalidCommandLogsPresenceFlagsWithoutSendingRequest() {
        AtomicInteger requests = new AtomicInteger();
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    requests.incrementAndGet();
                    return Mono.just(json(
                            200,
                            success("admin.niko000o.site", NOW.minusSeconds(30))));
                })
                .build();
        HumanVerificationCommand invalidCommand = new HumanVerificationCommand(
                "",
                "203.0.113.10",
                "challenge-id",
                "");

        try (DebugLogCapture logs =
                DebugLogCapture.start(HcaptchaHumanVerificationServiceImpl.class)) {
            StepVerifier.create(provider(client).verify(invalidCommand))
                    .expectErrorSatisfies(error ->
                            assertCode(error, AdminErrorCode.HCAPTCHA_REJECTED))
                    .verify();

            assertThat(requests).hasValue(0);
            assertThat(logs.joinedMessages())
                    .contains("failureStage=command")
                    .contains("safeReason=invalid_command")
                    .contains("responsePresent=false")
                    .contains("responseLengthValid=false")
                    .contains("clientIpPresent=true")
                    .contains("challengePresent=true")
                    .contains("actionValid=true")
                    .doesNotContain("203.0.113.10")
                    .doesNotContain("challenge-id");
        }
    }

    @Test
    void mapsProtocolFailuresAndTimeoutToUnavailable() {
        StepVerifier.create(provider(responseClient(502, "do-not-propagate")).verify(command()))
                .expectErrorSatisfies(error -> assertCode(error, AdminErrorCode.HCAPTCHA_UNAVAILABLE))
                .verify();
        StepVerifier.create(provider(responseClient(200, "{malformed")).verify(command()))
                .expectErrorSatisfies(error -> assertCode(error, AdminErrorCode.HCAPTCHA_UNAVAILABLE))
                .verify();

        HcaptchaHumanVerificationServiceImpl slow = new HcaptchaHumanVerificationServiceImpl(
                WebClient.builder().exchangeFunction(request -> Mono.never()).build(),
                "public-site-key",
                "server-secret",
                Set.of("admin.niko000o.site"),
                Duration.ofMinutes(2),
                Duration.ofMillis(10),
                CLOCK);
        try (DebugLogCapture logs =
                DebugLogCapture.start(HcaptchaHumanVerificationServiceImpl.class)) {
            StepVerifier.create(slow.verify(command()))
                    .expectErrorSatisfies(error ->
                            assertCode(error, AdminErrorCode.HCAPTCHA_UNAVAILABLE))
                    .verify();
            assertThat(logs.joinedMessages())
                    .contains("failureStage=transport")
                    .contains("safeReason=provider_timeout");
        }
    }

    private static HcaptchaHumanVerificationServiceImpl provider(WebClient client) {
        return provider(client, Set.of("admin.niko000o.site"));
    }

    private static HcaptchaHumanVerificationServiceImpl provider(
            WebClient client,
            Set<String> allowedHosts) {
        return new HcaptchaHumanVerificationServiceImpl(
                client,
                "public-site-key",
                "server-secret",
                allowedHosts,
                Duration.ofMinutes(2),
                CLOCK);
    }

    private static WebClient responseClient(int status, String body) {
        return WebClient.builder()
                .exchangeFunction(request -> Mono.just(json(status, body)))
                .build();
    }

    private static ClientResponse json(int status, String body) {
        return ClientResponse.create(HttpStatusCode.valueOf(status))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    private static HumanVerificationCommand command() {
        return HumanVerificationCommand.hcaptcha(
                "one-time-hcaptcha-token",
                "203.0.113.10",
                "challenge-id");
    }

    private static String success(String hostname, Instant challengeAt) {
        return """
                {
                  "success": true,
                  "challenge_ts": "%s",
                  "hostname": "%s"
                }
                """.formatted(challengeAt, hostname);
    }

    private static void assertCode(Throwable error, AdminErrorCode expected) {
        assertThat(error).isInstanceOf(AdminException.class);
        assertThat(((AdminException) error).code()).isEqualTo(expected);
    }

    private static void assertChallengeTimeReason(String body, String expectedReason) {
        try (DebugLogCapture logs =
                DebugLogCapture.start(HcaptchaHumanVerificationServiceImpl.class)) {
            StepVerifier.create(provider(responseClient(200, body)).verify(command()))
                    .expectErrorSatisfies(error ->
                            assertCode(error, AdminErrorCode.HCAPTCHA_REJECTED))
                    .verify();

            assertThat(logs.joinedMessages())
                    .contains("failureStage=challenge_time")
                    .contains("safeReason=" + expectedReason)
                    .doesNotContain("not-an-instant");
        }
    }
}
