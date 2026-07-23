package com.example.temperate.service.registration.service.turnstile.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.temperate.service.registration.enums.RegistrationDiagnosticCode;
import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.example.temperate.service.registration.service.turnstile.TurnstileVerificationService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 验证 Turnstile 返回的域名、动作、挑战绑定与有效期安全边界的测试。
 */
class TurnstileVerificationServiceImplTest {

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
    void acceptsSuccessfulBoundResponseAndSendsRequiredFormFields() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TurnstileVerificationService service = service(builder.build());

        server.expect(requestTo(SITE_VERIFY_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(Map.of(
                        "secret", SECRET,
                        "response", RESPONSE_TOKEN,
                        "remoteip", REMOTE_IP)))
                .andExpect(request -> {
                    String body = ((MockClientHttpRequest) request)
                            .getBodyAsString(StandardCharsets.UTF_8);
                    Map<String, String> form = parseForm(body);
                    assertThat(form).containsOnlyKeys(
                            "secret", "response", "remoteip", "idempotency_key");
                    assertThatCode(() -> UUID.fromString(form.get("idempotency_key")))
                            .doesNotThrowAnyException();
                })
                .andRespond(withSuccess(successBody(), MediaType.APPLICATION_JSON));

        service.verify(RESPONSE_TOKEN, REMOTE_IP, CHALLENGE_HANDLE);

        server.verify();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedBoundResponses")
    void rejectsResponseWhenBoundClaimsAreNotTrusted(
            String scenario,
            String responseBody,
            RegistrationDiagnosticCode expectedDiagnosticCode) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TurnstileVerificationService service = service(builder.build());
        server.expect(requestTo(SITE_VERIFY_URI))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        assertRejected(
                () -> service.verify(RESPONSE_TOKEN, REMOTE_IP, CHALLENGE_HANDLE),
                expectedDiagnosticCode);

        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 429, 502})
    void rejectsEveryNonTwoHundredResponse(int statusCode) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TurnstileVerificationService service = service(builder.build());
        server.expect(requestTo(SITE_VERIFY_URI))
                .andRespond(withStatus(HttpStatusCode.valueOf(statusCode))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(successBody()));

        assertRejected(
                () -> service.verify(RESPONSE_TOKEN, REMOTE_IP, CHALLENGE_HANDLE),
                RegistrationDiagnosticCode.SITEVERIFY_HTTP_ERROR);

        server.verify();
    }

    @Test
    void rejectsTimeoutAsControlledRegistrationError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TurnstileVerificationService service = service(builder.build());
        server.expect(requestTo(SITE_VERIFY_URI))
                .andRespond(withException(new SocketTimeoutException("simulated timeout")));

        assertRejected(
                () -> service.verify(RESPONSE_TOKEN, REMOTE_IP, CHALLENGE_HANDLE),
                RegistrationDiagnosticCode.SITEVERIFY_READ_TIMEOUT);

        server.verify();
    }

    @Test
    void distinguishesProviderConnectTimeoutFromReadTimeout() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TurnstileVerificationService service = service(builder.build());
        server.expect(requestTo(SITE_VERIFY_URI))
                .andRespond(withException(
                        new HttpConnectTimeoutException("simulated connect timeout")));

        assertRejected(
                () -> service.verify(RESPONSE_TOKEN, REMOTE_IP, CHALLENGE_HANDLE),
                RegistrationDiagnosticCode.SITEVERIFY_CONNECT_TIMEOUT);

        server.verify();
    }

    @Test
    void dedicatedRequestFactoryActivelyTimesOutASlowRealHttpResponse() throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService serverExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "turnstile-timeout-test");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(serverExecutor);
        server.createContext("/siteverify", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                Thread.sleep(500);
                byte[] body = successBody().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The client is expected to close the timed-out request.
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            URI localUri = URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort()
                    + "/siteverify");
            RestClient restClient = TurnstileVerificationServiceImpl.buildRestClient(
                    RestClient.builder(), Duration.ofMillis(100), Duration.ofMillis(100));
            TurnstileVerificationService service = new TurnstileVerificationServiceImpl(
                    restClient,
                    localUri,
                    SECRET,
                    Set.of(ALLOWED_HOST),
                    CLOCK,
                    Duration.ofMinutes(5));

            long started = System.nanoTime();
            assertRejected(
                    () -> service.verify(RESPONSE_TOKEN, REMOTE_IP, CHALLENGE_HANDLE),
                    RegistrationDiagnosticCode.SITEVERIFY_READ_TIMEOUT);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        } finally {
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void rejectsMalformedJsonAsControlledRegistrationError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TurnstileVerificationService service = service(builder.build());
        server.expect(requestTo(SITE_VERIFY_URI))
                .andRespond(withSuccess("{not-json", MediaType.APPLICATION_JSON));

        assertRejected(
                () -> service.verify(RESPONSE_TOKEN, REMOTE_IP, CHALLENGE_HANDLE),
                RegistrationDiagnosticCode.SITEVERIFY_MALFORMED_RESPONSE);

        server.verify();
    }

    @Test
    void rejectsResponseWithoutSuccessFlagAsMalformedProviderResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TurnstileVerificationService service = service(builder.build());
        server.expect(requestTo(SITE_VERIFY_URI))
                .andRespond(withSuccess(
                        """
                        {
                          "hostname": "register.example.test",
                          "action": "register"
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        assertRejected(
                () -> service.verify(RESPONSE_TOKEN, REMOTE_IP, CHALLENGE_HANDLE),
                RegistrationDiagnosticCode.SITEVERIFY_MALFORMED_RESPONSE);

        server.verify();
    }

    @Test
    void rejectsMalformedChallengeTimestampAsControlledRegistrationError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TurnstileVerificationService service = service(builder.build());
        server.expect(requestTo(SITE_VERIFY_URI))
                .andRespond(withSuccess(
                        responseBody(
                                true,
                                "not-a-timestamp",
                                ALLOWED_HOST,
                                "register",
                                CHALLENGE_HANDLE),
                        MediaType.APPLICATION_JSON));

        assertRejected(
                () -> service.verify(RESPONSE_TOKEN, REMOTE_IP, CHALLENGE_HANDLE),
                RegistrationDiagnosticCode.TIMESTAMP_INVALID);

        server.verify();
    }

    @Test
    void rejectsMissingChallengeTimestampAsControlledRegistrationError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TurnstileVerificationService service = service(builder.build());
        server.expect(requestTo(SITE_VERIFY_URI))
                .andRespond(withSuccess(
                        """
                        {
                          "success": true,
                          "hostname": "register.example.test",
                          "action": "register",
                          "cdata": "registration-challenge"
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        assertRejected(
                () -> service.verify(RESPONSE_TOKEN, REMOTE_IP, CHALLENGE_HANDLE),
                RegistrationDiagnosticCode.TIMESTAMP_INVALID);

        server.verify();
    }

    @Test
    void classifiesCloudflareTimeoutOrDuplicateWithoutExposingProviderDetails() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TurnstileVerificationService service = service(builder.build());
        server.expect(requestTo(SITE_VERIFY_URI))
                .andRespond(withSuccess(
                        """
                        {
                          "success": false,
                          "error-codes": ["timeout-or-duplicate"]
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        RegistrationException exception = assertRejected(
                () -> service.verify(RESPONSE_TOKEN, REMOTE_IP, CHALLENGE_HANDLE),
                RegistrationDiagnosticCode.TOKEN_TIMEOUT_OR_DUPLICATE);

        assertThat(exception.getMessage()).doesNotContain("timeout-or-duplicate");

        server.verify();
    }

    @Test
    void rejectsBlankSecretConfiguration() {
        assertRejected(() -> new TurnstileVerificationServiceImpl(
                RestClient.create(),
                SITE_VERIFY_URI,
                " ",
                Set.of(ALLOWED_HOST),
                CLOCK,
                Duration.ofMinutes(5)), RegistrationDiagnosticCode.CONFIGURATION_INVALID);
    }

    @Test
    void rejectsEmptyHostnameAllowlistConfiguration() {
        assertRejected(() -> new TurnstileVerificationServiceImpl(
                RestClient.create(),
                SITE_VERIFY_URI,
                SECRET,
                Set.of(),
                CLOCK,
                Duration.ofMinutes(5)), RegistrationDiagnosticCode.CONFIGURATION_INVALID);
    }

    @Test
    void rejectsNonPositiveChallengeWindowConfiguration() {
        assertRejected(() -> new TurnstileVerificationServiceImpl(
                RestClient.create(), SITE_VERIFY_URI, SECRET, Set.of(ALLOWED_HOST), CLOCK, Duration.ZERO),
                RegistrationDiagnosticCode.CONFIGURATION_INVALID);
    }

    @Test
    void rejectsRelativeSiteVerifyUriConfiguration() {
        assertRejected(() -> new TurnstileVerificationServiceImpl(
                RestClient.create(),
                URI.create("/siteverify"),
                SECRET,
                Set.of(ALLOWED_HOST),
                CLOCK,
                Duration.ofMinutes(5)), RegistrationDiagnosticCode.CONFIGURATION_INVALID);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidVerificationInputs")
    void rejectsInvalidInputWithoutCallingTurnstile(
            String scenario, String responseToken, String remoteIp, String challengeHandle) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TurnstileVerificationService service = service(builder.build());

        assertRejected(
                () -> service.verify(responseToken, remoteIp, challengeHandle),
                RegistrationDiagnosticCode.INPUT_INVALID);

        server.verify();
    }

    private static TurnstileVerificationService service(RestClient restClient) {
        return new TurnstileVerificationServiceImpl(
                restClient,
                SITE_VERIFY_URI,
                SECRET,
                Set.of(ALLOWED_HOST),
                CLOCK,
                Duration.ofMinutes(5));
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
                Arguments.of("success is false", responseBody(
                        false, "2026-07-13T17:58:00Z", ALLOWED_HOST, "register", CHALLENGE_HANDLE),
                        RegistrationDiagnosticCode.CLOUDFLARE_TOKEN_REJECTED),
                Arguments.of("hostname is outside the allowlist", responseBody(
                        true,
                        "2026-07-13T17:58:00Z",
                        "attacker.example.test",
                        "register",
                        CHALLENGE_HANDLE), RegistrationDiagnosticCode.HOSTNAME_MISMATCH),
                Arguments.of("action is not register", responseBody(
                        true, "2026-07-13T17:58:00Z", ALLOWED_HOST, "login", CHALLENGE_HANDLE),
                        RegistrationDiagnosticCode.ACTION_MISMATCH),
                Arguments.of("cdata is not the challenge handle", responseBody(
                        true, "2026-07-13T17:58:00Z", ALLOWED_HOST, "register", "other-handle"),
                        RegistrationDiagnosticCode.CDATA_MISMATCH),
                Arguments.of("challenge timestamp is too old", responseBody(
                        true, "2026-07-13T17:54:59Z", ALLOWED_HOST, "register", CHALLENGE_HANDLE),
                        RegistrationDiagnosticCode.TIMESTAMP_EXPIRED),
                Arguments.of("challenge timestamp is in the future", responseBody(
                        true, "2026-07-13T18:00:01Z", ALLOWED_HOST, "register", CHALLENGE_HANDLE),
                        RegistrationDiagnosticCode.TIMESTAMP_FUTURE));
    }

    private static Stream<Arguments> invalidVerificationInputs() {
        return Stream.of(
                Arguments.of("response token is null", null, REMOTE_IP, CHALLENGE_HANDLE),
                Arguments.of("response token is blank", " ", REMOTE_IP, CHALLENGE_HANDLE),
                Arguments.of("remote IP is null", RESPONSE_TOKEN, null, CHALLENGE_HANDLE),
                Arguments.of("remote IP is blank", RESPONSE_TOKEN, " ", CHALLENGE_HANDLE),
                Arguments.of("challenge handle is null", RESPONSE_TOKEN, REMOTE_IP, null),
                Arguments.of("challenge handle is blank", RESPONSE_TOKEN, REMOTE_IP, " "));
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
                """.formatted(success, challengeTimestamp, hostname, action, cdata);
    }

    private static RegistrationException assertRejected(
            Runnable invocation, RegistrationDiagnosticCode expectedDiagnosticCode) {
        try {
            invocation.run();
        } catch (RegistrationException exception) {
            assertThat(exception.code()).isEqualTo(RegistrationErrorCode.TURNSTILE_REJECTED);
            assertThat(exception.diagnosticCode()).contains(expectedDiagnosticCode);
            return exception;
        }
        throw new AssertionError("Expected Turnstile verification to be rejected");
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
}
