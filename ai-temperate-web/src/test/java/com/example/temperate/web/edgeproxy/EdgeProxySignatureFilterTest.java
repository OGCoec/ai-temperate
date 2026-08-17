package com.example.temperate.web.edgeproxy;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.temperate.service.user.voice.diagnostic.VoiceDiagnosticContext;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 验证生产 H5 与 Android 请求必须经过 Worker，并覆盖切换模式和伪造边缘头的失败关闭语义。
 */
class EdgeProxySignatureFilterTest {

    private static final Instant NOW = Instant.parse("2026-07-24T18:00:00Z");
    private static final byte[] SECRET =
            "edge-proxy-filter-secret-0123456789abc".getBytes(StandardCharsets.UTF_8);

    @Test
    void requiredModeRejectsUnsignedBrowserRequest() throws Exception {
        EdgeProxySignatureFilter filter = filter(EdgeProxyMode.REQUIRED);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/auth/csrf");
        request.addHeader("Origin", "https://niko000o.site");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .contains("EDGE_PROXY_SIGNATURE_INVALID");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void requiredModeRejectsUnsignedUnknownApiBeforeSpringRouteResolution()
            throws Exception {
        EdgeProxySignatureFilter filter = filter(EdgeProxyMode.REQUIRED);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/not-exist");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString())
                .contains("EDGE_PROXY_SIGNATURE_INVALID");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void requiredModeRejectsUnsignedBrowserWebSocketUpgrade() throws Exception {
        EdgeProxySignatureFilter filter = filter(EdgeProxyMode.REQUIRED);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/ws/voice");
        request.addHeader("Origin", "https://niko000o.site");
        request.addHeader("Upgrade", "websocket");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .contains("EDGE_PROXY_SIGNATURE_INVALID");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void requiredModeAllowsSignedBrowserWebSocketUpgrade() throws Exception {
        EdgeProxySignatureFilter filter = filter(EdgeProxyMode.REQUIRED);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/ws/voice");
        request.setRequestURI("/ws/voice");
        request.addHeader("Origin", "https://niko000o.site");
        request.addHeader("Upgrade", "websocket");
        addSignature(request, "niko000o.site");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(request.getAttribute(
                TrustedExternalHostResolver.VERIFIED_EXTERNAL_HOST_ATTRIBUTE))
                .isEqualTo("niko000o.site");
    }

    @Test
    void optionalModeRejectsIncompleteWebSocketEdgeHeaders() throws Exception {
        EdgeProxySignatureFilter filter = filter(EdgeProxyMode.OPTIONAL);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/ws/voice");
        request.addHeader("Origin", "https://niko000o.site");
        request.addHeader("Upgrade", "websocket");
        request.addHeader(
                EdgeProxySignatureVerifier.EXTERNAL_HOST_HEADER,
                "niko000o.site");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void requiredModeRejectsForgedWebSocketSignature() throws Exception {
        EdgeProxySignatureFilter filter = filter(EdgeProxyMode.REQUIRED);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/ws/voice");
        request.setRequestURI("/ws/voice");
        request.addHeader("Origin", "https://niko000o.site");
        request.addHeader("Upgrade", "websocket");
        addSignature(request, "niko000o.site");
        request.removeHeader(EdgeProxySignatureVerifier.SIGNATURE_HEADER);
        request.addHeader(EdgeProxySignatureVerifier.SIGNATURE_HEADER, "forged");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void requiredModeRejectsExpiredNativeSignature() throws Exception {
        EdgeProxySignatureFilter filter = filter(EdgeProxyMode.REQUIRED);
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/_edge/pre-auth");
        request.setRequestURI("/api/_edge/pre-auth");
        request.addHeader("X-Client-Platform", "ANDROID");
        addSignatureAt(request, "niko000o.site", NOW.minus(Duration.ofMinutes(5)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void optionalModeAllowsUnsignedBrowserWebSocketDuringCutover() throws Exception {
        EdgeProxySignatureFilter filter = filter(EdgeProxyMode.OPTIONAL);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/ws/voice");
        request.addHeader("Origin", "https://niko000o.site");
        request.addHeader("Upgrade", "websocket");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void requiredModeRejectsUnsignedNativeWebSocketWithoutOrigin() throws Exception {
        EdgeProxySignatureFilter filter = filter(EdgeProxyMode.REQUIRED);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/ws/voice");
        request.addHeader("X-Client-Platform", "ANDROID");
        request.addHeader("Upgrade", "websocket");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .contains("EDGE_PROXY_SIGNATURE_INVALID");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void requiredModeRejectsUnsignedNativeRequestWithoutOrigin() throws Exception {
        EdgeProxySignatureFilter filter = filter(EdgeProxyMode.REQUIRED);
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/auth/session/bootstrap");
        request.addHeader("X-Client-Platform", "ANDROID");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .contains("EDGE_PROXY_SIGNATURE_INVALID");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void requiredModeAllowsSignedNativeRequestWithoutOrigin() throws Exception {
        EdgeProxySignatureFilter filter = filter(EdgeProxyMode.REQUIRED);
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/auth/session/bootstrap");
        request.setRequestURI("/api/auth/session/bootstrap");
        request.addHeader("X-Client-Platform", "ANDROID");
        addSignature(request, "niko000o.site");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(request.getAttribute(
                TrustedExternalHostResolver.VERIFIED_EXTERNAL_HOST_ATTRIBUTE))
                .isEqualTo("niko000o.site");
    }

    @Test
    void requiredModeAllowsSignedNativeWebSocketWithoutOrigin() throws Exception {
        EdgeProxySignatureFilter filter = filter(EdgeProxyMode.REQUIRED);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/ws/voice");
        request.setRequestURI("/ws/voice");
        request.addHeader("X-Client-Platform", "ANDROID");
        request.addHeader("Upgrade", "websocket");
        addSignature(request, "niko000o.site");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(request.getAttribute(
                TrustedExternalHostResolver.VERIFIED_EXTERNAL_HOST_ATTRIBUTE))
                .isEqualTo("niko000o.site");
    }

    @Test
    void optionalModeAllowsUnsignedBrowserRequestDuringCutover() throws Exception {
        EdgeProxySignatureFilter filter = filter(EdgeProxyMode.OPTIONAL);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/auth/csrf");
        request.addHeader("Origin", "https://niko000o.site");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void optionalModeAllowsUnsignedNativeRequestDuringCutover() throws Exception {
        EdgeProxySignatureFilter filter = filter(EdgeProxyMode.OPTIONAL);
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/_edge/pre-auth");
        request.addHeader("X-Client-Platform", "ANDROID");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void requiredModeDoesNotFilterUnprotectedPaths() throws Exception {
        EdgeProxySignatureFilter filter = filter(EdgeProxyMode.REQUIRED);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void optionalModeRejectsIncompleteEdgeHeaders() throws Exception {
        EdgeProxySignatureFilter filter = filter(EdgeProxyMode.OPTIONAL);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/auth/csrf");
        request.addHeader(
                EdgeProxySignatureVerifier.EXTERNAL_HOST_HEADER,
                "niko000o.site");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void disabledModeIgnoresEdgeHeadersForLocalDevelopment() throws Exception {
        EdgeProxySignatureFilter filter = filter(EdgeProxyMode.DISABLED);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/auth/csrf");
        request.addHeader(
                EdgeProxySignatureVerifier.EXTERNAL_HOST_HEADER,
                "local-development");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void validWorkerSignaturePublishesTrustedExternalHost() throws Exception {
        EdgeProxySignatureFilter filter = filter(EdgeProxyMode.REQUIRED);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/admin/auth/state");
        request.setRequestURI("/api/admin/auth/state");
        request.addHeader("Origin", "https://admin.niko000o.site");
        addSignature(request, "admin.niko000o.site");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(request.getAttribute(
                TrustedExternalHostResolver.VERIFIED_EXTERNAL_HOST_ATTRIBUTE))
                .isEqualTo("admin.niko000o.site");
        assertThat(request.getAttribute(
                TrustedEdgeNetworkContextResolver.VERIFIED_NETWORK_CONTEXT_ATTRIBUTE))
                .isInstanceOf(TrustedEdgeNetworkContext.class);
    }

    @Test
    void logsVerifiedVoiceSignatureWithoutExposingSignatureOrNetworkHeaders()
            throws Exception {
        EdgeProxySignatureFilter filter = filter(EdgeProxyMode.REQUIRED);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/ws/voice");
        request.setRequestURI("/ws/voice");
        request.setAttribute(
                VoiceDiagnosticContext.ATTRIBUTE,
                new VoiceDiagnosticContext("trace-edge-verified", "test-ray-ord"));
        addSignature(request, "niko000o.site");
        String suppliedSignature = request.getHeader(
                EdgeProxySignatureVerifier.SIGNATURE_HEADER);
        LoggerCapture capture = capture();
        try {
            filter.doFilter(
                    request,
                    new MockHttpServletResponse(),
                    new MockFilterChain());

            assertThat(capture.messages()).singleElement().satisfies(message -> {
                assertThat(message).contains(
                        "event=voice_ws_edge_signature",
                        "traceId=trace-edge-verified",
                        "edgeRay=test-ray-ord",
                        "mode=REQUIRED",
                        "edgeHeadersPresent=true",
                        "edgeRayTrusted=true",
                        "outcome=VERIFIED");
                assertThat(message).doesNotContain(
                        suppliedSignature,
                        "203.0.113.10",
                        "41.8781",
                        "-87.6298");
            });
        } finally {
            capture.close();
        }
    }

    @Test
    void logsMissingRequiredAndInvalidVoiceSignaturesAsDistinctOutcomes()
            throws Exception {
        LoggerCapture capture = capture();
        try {
            MockHttpServletRequest missing =
                    new MockHttpServletRequest("GET", "/ws/voice");
            filter(EdgeProxyMode.REQUIRED).doFilter(
                    missing,
                    new MockHttpServletResponse(),
                    new MockFilterChain());

            MockHttpServletRequest invalid =
                    new MockHttpServletRequest("GET", "/ws/voice");
            invalid.setRequestURI("/ws/voice");
            addSignature(invalid, "niko000o.site");
            invalid.removeHeader(EdgeProxySignatureVerifier.SIGNATURE_HEADER);
            invalid.addHeader(EdgeProxySignatureVerifier.SIGNATURE_HEADER, "forged");
            filter(EdgeProxyMode.REQUIRED).doFilter(
                    invalid,
                    new MockHttpServletResponse(),
                    new MockFilterChain());

            assertThat(capture.messages()).hasSize(2);
            assertThat(capture.messages().get(0)).contains(
                    "outcome=MISSING_REQUIRED",
                    "edgeRayTrusted=false");
            assertThat(capture.messages().get(1)).contains(
                    "outcome=INVALID",
                    "edgeRayTrusted=false");
            assertThat(capture.messages()).allSatisfy(message ->
                    assertThat(message).doesNotContain("forged"));
        } finally {
            capture.close();
        }
    }

    private static EdgeProxySignatureFilter filter(EdgeProxyMode mode) {
        EdgeProxyProperties properties = new EdgeProxyProperties(
                mode,
                Base64.getEncoder().encodeToString(SECRET),
                Duration.ofSeconds(30));
        EdgeProxySignatureVerifier verifier = new EdgeProxySignatureVerifier(
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new EdgeProxySignatureFilter(properties, verifier);
    }

    private static LoggerCapture capture() {
        Logger logger = (Logger) LoggerFactory.getLogger(EdgeProxySignatureFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return new LoggerCapture(logger, appender);
    }

    private static void addSignature(
            MockHttpServletRequest request,
            String externalHost) {
        addSignatureAt(request, externalHost, NOW);
    }

    private static void addSignatureAt(
            MockHttpServletRequest request,
            String externalHost,
            Instant issuedAt) {
        String ray = "test-ray-ord";
        long timestamp = issuedAt.getEpochSecond();
        String canonical = String.join(
                "\n",
                "v2",
                request.getMethod(),
                request.getRequestURI(),
                externalHost,
                Long.toString(timestamp),
                ray,
                "203.0.113.10",
                "US",
                "64500",
                "41.8781",
                "-87.6298");
        request.addHeader(EdgeProxySignatureVerifier.VERSION_HEADER, "v2");
        request.addHeader(EdgeProxySignatureVerifier.EXTERNAL_HOST_HEADER, externalHost);
        request.addHeader(EdgeProxySignatureVerifier.TIMESTAMP_HEADER, Long.toString(timestamp));
        request.addHeader(EdgeProxySignatureVerifier.RAY_HEADER, ray);
        request.addHeader(EdgeProxySignatureVerifier.CLIENT_IP_HEADER, "203.0.113.10");
        request.addHeader(EdgeProxySignatureVerifier.COUNTRY_HEADER, "US");
        request.addHeader(EdgeProxySignatureVerifier.ASN_HEADER, "64500");
        request.addHeader(EdgeProxySignatureVerifier.LATITUDE_HEADER, "41.8781");
        request.addHeader(EdgeProxySignatureVerifier.LONGITUDE_HEADER, "-87.6298");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
            request.addHeader(
                    EdgeProxySignatureVerifier.SIGNATURE_HEADER,
                    Base64.getUrlEncoder().withoutPadding().encodeToString(
                            mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record LoggerCapture(
            Logger logger,
            ListAppender<ILoggingEvent> appender) implements AutoCloseable {

        private java.util.List<String> messages() {
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
