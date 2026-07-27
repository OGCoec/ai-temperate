package com.example.temperate.service.humanverification.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.temperate.service.registration.enums.RegistrationDiagnosticCode;
import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.example.temperate.service.humanverification.HumanVerificationCommand;
import com.example.temperate.service.humanverification.HumanVerificationService;
import com.example.temperate.service.humanverification.HumanVerificationType;
import com.example.temperate.service.humanverification.exception.HumanVerificationUnavailableException;
import com.sun.net.httpserver.HttpServer;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.channels.ClosedChannelException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * 验证 WebClient Turnstile 调用的惰性、传输失败分类和供应商声明绑定安全边界。
 */
class TurnstileHumanVerificationServiceImplTest {

    private static final URI SITE_VERIFY_URI =
            URI.create("https://turnstile.example.test/siteverify");
    private static final Instant NOW = Instant.parse("2026-07-13T18:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String SECRET = "test-secret-key";
    private static final String RESPONSE_TOKEN = "test-response-token";
    private static final String REMOTE_IP = "203.0.113.10";
    private static final String CHALLENGE_HANDLE = "registration-challenge";
    private static final String ALLOWED_HOST = "register.example.test";

    @Test
    void exposesStableTurnstileType() {
        assertThat(service(responseClient(200, successBody())).type())
                .isEqualTo(HumanVerificationType.TURNSTILE);
    }

    @Test
    void acceptsSuccessfulBoundResponseAndSendsRequiredFormFields() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = startServer(exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = successBody().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(
                    HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        });
        try {
            URI localUri = localUri(server);
            HumanVerificationService service = service(
                    TurnstileHumanVerificationServiceImpl.buildWebClient(
                            WebClient.builder(),
                            Duration.ofSeconds(2),
                            Duration.ofSeconds(2)),
                    localUri);

            StepVerifier.create(service.verify(registerCommand()))
                    .verifyComplete();

            Map<String, String> form = parseForm(requestBody.get());
            assertThat(form).containsEntry("secret", SECRET)
                    .containsEntry("response", RESPONSE_TOKEN)
                    .containsEntry("remoteip", REMOTE_IP)
                    .containsOnlyKeys(
                            "secret",
                            "response",
                            "remoteip",
                            "idempotency_key");
            assertThatCode(() -> UUID.fromString(form.get("idempotency_key")))
                    .doesNotThrowAnyException();
        } finally {
            stopServer(server);
        }
    }

    @Test
    void acceptsExplicitLoginActionFromUnifiedCommand() {
        HumanVerificationService service = service(responseClient(
                200,
                responseBody(
                        true,
                        "2026-07-13T17:58:00Z",
                        ALLOWED_HOST,
                        "login",
                        CHALLENGE_HANDLE)));

        StepVerifier.create(service.verify(HumanVerificationCommand.turnstile(
                        RESPONSE_TOKEN,
                        REMOTE_IP,
                        CHALLENGE_HANDLE,
                        "login")))
                .verifyComplete();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedBoundResponses")
    void rejectsResponseWhenBoundClaimsAreNotTrusted(
            String scenario,
            String responseBody,
            RegistrationDiagnosticCode expectedDiagnosticCode) {
        assertRejected(
                service(responseClient(200, responseBody))
                        .verify(registerCommand()),
                expectedDiagnosticCode);
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 429, 502})
    void treatsEveryNonTwoHundredResponseAsProviderUnavailable(int statusCode) {
        assertUnavailable(
                service(responseClient(statusCode, successBody()))
                        .verify(registerCommand()));
    }

    @Test
    void treatsReadTimeoutAsProviderUnavailable() {
        assertUnavailable(
                service(failingClient(ReadTimeoutException.INSTANCE))
                        .verify(registerCommand()));
    }

    @Test
    void treatsConnectTimeoutAsProviderUnavailable() {
        assertUnavailable(
                service(failingClient(new ConnectTimeoutException(
                                "simulated connect timeout")))
                        .verify(registerCommand()));
    }

    @Test
    void treatsClosedChannelDuringTlsHandshakeAsProviderUnavailable() {
        ClosedChannelException closedChannel = new ClosedChannelException();

        HumanVerificationUnavailableException exception = assertUnavailable(
                service(failingClient(closedChannel))
                        .verify(registerCommand()));

        assertThat(exception.verificationType())
                .isEqualTo(HumanVerificationType.TURNSTILE);
        assertThat(exception.getCause()).isSameAs(closedChannel);
    }

    @Test
    void reactorNettyConnectorActivelyTimesOutASlowRealHttpResponse()
            throws Exception {
        HttpServer server = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(500);
                byte[] body = successBody().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set(
                        HttpHeaders.CONTENT_TYPE,
                        MediaType.APPLICATION_JSON_VALUE);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // 客户端按预期超时后会关闭连接，服务端写响应可能失败。
            }
        });
        try {
            HumanVerificationService service = service(
                    TurnstileHumanVerificationServiceImpl.buildWebClient(
                            WebClient.builder(),
                            Duration.ofMillis(100),
                            Duration.ofMillis(100)),
                    localUri(server));
            long started = System.nanoTime();

            assertUnavailable(service.verify(registerCommand()));

            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofSeconds(2));
        } finally {
            stopServer(server);
        }
    }

    @Test
    void treatsMalformedJsonAsProviderUnavailable() {
        assertUnavailable(
                service(responseClient(200, "{not-json"))
                        .verify(registerCommand()));
    }

    @Test
    void treatsEmptyResponseAsProviderUnavailable() {
        assertUnavailable(
                service(responseClient(200, ""))
                        .verify(registerCommand()));
    }

    @Test
    void treatsResponseWithoutSuccessFlagAsProviderUnavailable() {
        assertUnavailable(
                service(responseClient(
                                200,
                                """
                                {
                                  "hostname": "register.example.test",
                                  "action": "register"
                                }
                                """))
                        .verify(registerCommand()));
    }

    @Test
    void treatsMalformedChallengeTimestampAsProviderUnavailable() {
        assertUnavailable(
                service(responseClient(
                                200,
                                responseBody(
                                        true,
                                        "not-a-timestamp",
                                        ALLOWED_HOST,
                                        "register",
                                        CHALLENGE_HANDLE)))
                        .verify(registerCommand()));
    }

    @Test
    void treatsMissingChallengeTimestampAsProviderUnavailable() {
        assertUnavailable(
                service(responseClient(
                                200,
                                """
                                {
                                  "success": true,
                                  "hostname": "register.example.test",
                                  "action": "register",
                                  "cdata": "registration-challenge"
                                }
                                """))
                        .verify(registerCommand()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "missing-input-secret",
            "invalid-input-secret",
            "bad-request",
            "internal-error",
            "future-provider-error"
    })
    void treatsProviderInfrastructureCodesAsUnavailable(String providerCode) {
        assertUnavailable(
                service(responseClient(
                                200,
                                """
                                {
                                  "success": false,
                                  "error-codes": ["%s"]
                                }
                                """.formatted(providerCode)))
                        .verify(registerCommand()));
    }

    @Test
    void keepsInvalidResponseTokenAsControlledRejection() {
        assertRejected(
                service(responseClient(
                                200,
                                """
                                {
                                  "success": false,
                                  "error-codes": ["invalid-input-response"]
                                }
                                """))
                        .verify(registerCommand()),
                RegistrationDiagnosticCode.CLOUDFLARE_TOKEN_REJECTED);
    }

    @Test
    void classifiesCloudflareTimeoutOrDuplicateWithoutExposingProviderDetails() {
        RegistrationException exception = assertRejected(
                service(responseClient(
                                200,
                                """
                                {
                                  "success": false,
                                  "error-codes": ["timeout-or-duplicate"]
                                }
                                """))
                        .verify(registerCommand()),
                RegistrationDiagnosticCode.TOKEN_TIMEOUT_OR_DUPLICATE);

        assertThat(exception.getMessage()).doesNotContain("timeout-or-duplicate");
    }

    @Test
    void callingVerifyIsLazyAndReturnsBeforeTheProviderResponds() {
        AtomicInteger requests = new AtomicInteger();
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    requests.incrementAndGet();
                    return Mono.never();
                })
                .build();
        HumanVerificationService service = service(webClient);

        long started = System.nanoTime();
        Mono<Void> operation =
                service.verify(registerCommand());

        assertThat(requests).hasValue(0);
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(Duration.ofMillis(250));
        StepVerifier.create(operation)
                .then(() -> assertThat(requests).hasValue(1))
                .thenCancel()
                .verify();
    }

    @Test
    void cancellingSubscriptionCancelsThePendingProviderRequest() {
        AtomicBoolean cancelled = new AtomicBoolean();
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.<ClientResponse>never()
                        .doOnCancel(() -> cancelled.set(true)))
                .build();

        StepVerifier.create(service(webClient)
                        .verify(registerCommand()))
                .thenCancel()
                .verify();

        assertThat(cancelled).isTrue();
    }

    @Test
    void oneSubscriptionSendsExactlyOneRequest() {
        AtomicInteger requests = new AtomicInteger();
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    requests.incrementAndGet();
                    return Mono.just(jsonResponse(200, successBody()));
                })
                .build();

        StepVerifier.create(service(webClient)
                        .verify(registerCommand()))
                .verifyComplete();

        assertThat(requests).hasValue(1);
    }

    @Test
    void rejectsBlankSecretConfiguration() {
        assertConfigurationRejected(
                () -> new TurnstileHumanVerificationServiceImpl(
                        WebClient.create(),
                        SITE_VERIFY_URI,
                        " ",
                        Set.of(ALLOWED_HOST),
                        CLOCK,
                        Duration.ofMinutes(5)),
                RegistrationDiagnosticCode.CONFIGURATION_INVALID);
    }

    @Test
    void rejectsEmptyHostnameAllowlistConfiguration() {
        assertConfigurationRejected(
                () -> new TurnstileHumanVerificationServiceImpl(
                        WebClient.create(),
                        SITE_VERIFY_URI,
                        SECRET,
                        Set.of(),
                        CLOCK,
                        Duration.ofMinutes(5)),
                RegistrationDiagnosticCode.CONFIGURATION_INVALID);
    }

    @Test
    void rejectsNonPositiveChallengeWindowConfiguration() {
        assertConfigurationRejected(
                () -> new TurnstileHumanVerificationServiceImpl(
                        WebClient.create(),
                        SITE_VERIFY_URI,
                        SECRET,
                        Set.of(ALLOWED_HOST),
                        CLOCK,
                        Duration.ZERO),
                RegistrationDiagnosticCode.CONFIGURATION_INVALID);
    }

    @Test
    void rejectsRelativeSiteVerifyUriConfiguration() {
        assertConfigurationRejected(
                () -> new TurnstileHumanVerificationServiceImpl(
                        WebClient.create(),
                        URI.create("/siteverify"),
                        SECRET,
                        Set.of(ALLOWED_HOST),
                        CLOCK,
                        Duration.ofMinutes(5)),
                RegistrationDiagnosticCode.CONFIGURATION_INVALID);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidVerificationInputs")
    void rejectsInvalidInputWithoutCallingTurnstile(
            String scenario,
            String responseToken,
            String remoteIp,
            String challengeHandle) {
        AtomicInteger requests = new AtomicInteger();
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    requests.incrementAndGet();
                    return Mono.just(jsonResponse(200, successBody()));
                })
                .build();

        assertRejected(
                service(webClient).verify(HumanVerificationCommand.turnstile(
                        responseToken, remoteIp, challengeHandle, "register")),
                RegistrationDiagnosticCode.INPUT_INVALID);
        assertThat(requests).hasValue(0);
    }

    private static HumanVerificationService service(WebClient webClient) {
        return service(webClient, SITE_VERIFY_URI);
    }

    private static HumanVerificationCommand registerCommand() {
        return HumanVerificationCommand.turnstile(
                RESPONSE_TOKEN,
                REMOTE_IP,
                CHALLENGE_HANDLE,
                "register");
    }

    private static HumanVerificationService service(
            WebClient webClient, URI siteVerifyUri) {
        return new TurnstileHumanVerificationServiceImpl(
                webClient,
                siteVerifyUri,
                SECRET,
                Set.of(ALLOWED_HOST),
                CLOCK,
                Duration.ofMinutes(5));
    }

    private static WebClient responseClient(int statusCode, String body) {
        return WebClient.builder()
                .exchangeFunction(request -> Mono.just(
                        jsonResponse(statusCode, body)))
                .build();
    }

    private static WebClient failingClient(Throwable failure) {
        return WebClient.builder()
                .exchangeFunction(request -> Mono.error(
                        new WebClientRequestException(
                                failure,
                                HttpMethod.POST,
                                SITE_VERIFY_URI,
                                HttpHeaders.EMPTY)))
                .build();
    }

    private static ClientResponse jsonResponse(int statusCode, String body) {
        return ClientResponse.create(HttpStatusCode.valueOf(statusCode))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header("CF-Ray", "test-ray-id")
                .body(body)
                .build();
    }

    private static String successBody() {
        return """
                {
                  "success": true,
                  "challenge_ts": "2026-07-13T17:58:00Z",
                  "hostname": "register.example.test",
                  "action": "register",
                  "cdata": "registration-challenge"
                }
                """;
    }

    private static Stream<Arguments> rejectedBoundResponses() {
        return Stream.of(
                Arguments.of(
                        "response token is rejected",
                        """
                        {
                          "success": false,
                          "error-codes": ["invalid-input-response"]
                        }
                        """,
                        RegistrationDiagnosticCode.CLOUDFLARE_TOKEN_REJECTED),
                Arguments.of(
                        "hostname is outside the allowlist",
                        responseBody(
                                true,
                                "2026-07-13T17:58:00Z",
                                "attacker.example.test",
                                "register",
                                CHALLENGE_HANDLE),
                        RegistrationDiagnosticCode.HOSTNAME_MISMATCH),
                Arguments.of(
                        "action is not register",
                        responseBody(
                                true,
                                "2026-07-13T17:58:00Z",
                                ALLOWED_HOST,
                                "login",
                                CHALLENGE_HANDLE),
                        RegistrationDiagnosticCode.ACTION_MISMATCH),
                Arguments.of(
                        "cdata is not the challenge handle",
                        responseBody(
                                true,
                                "2026-07-13T17:58:00Z",
                                ALLOWED_HOST,
                                "register",
                                "other-handle"),
                        RegistrationDiagnosticCode.CDATA_MISMATCH),
                Arguments.of(
                        "challenge timestamp is too old",
                        responseBody(
                                true,
                                "2026-07-13T17:54:59Z",
                                ALLOWED_HOST,
                                "register",
                                CHALLENGE_HANDLE),
                        RegistrationDiagnosticCode.TIMESTAMP_EXPIRED),
                Arguments.of(
                        "challenge timestamp is in the future",
                        responseBody(
                                true,
                                "2026-07-13T18:00:01Z",
                                ALLOWED_HOST,
                                "register",
                                CHALLENGE_HANDLE),
                        RegistrationDiagnosticCode.TIMESTAMP_FUTURE));
    }

    private static Stream<Arguments> invalidVerificationInputs() {
        return Stream.of(
                Arguments.of(
                        "response token is null",
                        null,
                        REMOTE_IP,
                        CHALLENGE_HANDLE),
                Arguments.of(
                        "response token is blank",
                        " ",
                        REMOTE_IP,
                        CHALLENGE_HANDLE),
                Arguments.of(
                        "remote IP is null",
                        RESPONSE_TOKEN,
                        null,
                        CHALLENGE_HANDLE),
                Arguments.of(
                        "remote IP is blank",
                        RESPONSE_TOKEN,
                        " ",
                        CHALLENGE_HANDLE),
                Arguments.of(
                        "challenge handle is null",
                        RESPONSE_TOKEN,
                        REMOTE_IP,
                        null),
                Arguments.of(
                        "challenge handle is blank",
                        RESPONSE_TOKEN,
                        REMOTE_IP,
                        " "));
    }

    private static String responseBody(
            boolean success,
            String challengeTimestamp,
            String hostname,
            String action,
            String cdata) {
        return """
                {
                  "success": %s,
                  "challenge_ts": "%s",
                  "hostname": "%s",
                  "action": "%s",
                  "cdata": "%s"
                }
                """.formatted(
                success,
                challengeTimestamp,
                hostname,
                action,
                cdata);
    }

    private static RegistrationException assertRejected(
            Mono<Void> operation,
            RegistrationDiagnosticCode expectedDiagnosticCode) {
        AtomicReference<RegistrationException> rejection = new AtomicReference<>();
        StepVerifier.create(operation)
                .expectErrorSatisfies(failure -> {
                    assertThat(failure)
                            .isInstanceOf(RegistrationException.class);
                    RegistrationException exception =
                            (RegistrationException) failure;
                    assertThat(exception.code())
                            .isEqualTo(RegistrationErrorCode.TURNSTILE_REJECTED);
                    assertThat(exception.diagnosticCode())
                            .contains(expectedDiagnosticCode);
                    rejection.set(exception);
                })
                .verify();
        return rejection.get();
    }

    private static HumanVerificationUnavailableException assertUnavailable(
            Mono<Void> operation) {
        AtomicReference<HumanVerificationUnavailableException> unavailable =
                new AtomicReference<>();
        StepVerifier.create(operation)
                .expectErrorSatisfies(failure -> {
                    assertThat(failure)
                            .isInstanceOf(HumanVerificationUnavailableException.class);
                    HumanVerificationUnavailableException exception =
                            (HumanVerificationUnavailableException) failure;
                    assertThat(exception.verificationType())
                            .isEqualTo(HumanVerificationType.TURNSTILE);
                    unavailable.set(exception);
                })
                .verify();
        return unavailable.get();
    }

    private static void assertConfigurationRejected(
            Runnable invocation,
            RegistrationDiagnosticCode expectedDiagnosticCode) {
        try {
            invocation.run();
        } catch (RegistrationException exception) {
            assertThat(exception.code())
                    .isEqualTo(RegistrationErrorCode.TURNSTILE_REJECTED);
            assertThat(exception.diagnosticCode())
                    .contains(expectedDiagnosticCode);
            return;
        }
        throw new AssertionError(
                "Expected Turnstile configuration to be rejected");
    }

    private static HttpServer startServer(
            ThrowingExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(
                    runnable, "turnstile-webclient-test");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/siteverify", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        SERVER_EXECUTORS.put(server, executor);
        return server;
    }

    private static final Map<HttpServer, ExecutorService> SERVER_EXECUTORS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static void stopServer(HttpServer server) {
        server.stop(0);
        ExecutorService executor = SERVER_EXECUTORS.remove(server);
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private static URI localUri(HttpServer server) {
        return URI.create(
                "http://127.0.0.1:"
                        + server.getAddress().getPort()
                        + "/siteverify");
    }

    private static Map<String, String> parseForm(String body) {
        return Arrays.stream(body.split("&"))
                .map(part -> part.split("=", 2))
                .collect(Collectors.toMap(
                        pair -> decode(pair[0]),
                        pair -> pair.length == 2 ? decode(pair[1]) : ""));
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface ThrowingExchangeHandler {

        void handle(com.sun.net.httpserver.HttpExchange exchange)
                throws IOException;
    }
}
