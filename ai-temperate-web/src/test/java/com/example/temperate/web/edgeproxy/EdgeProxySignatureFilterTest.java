package com.example.temperate.web.edgeproxy;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 验证边缘签名过滤器在生产 H5 请求上失败关闭，同时保持 Android 直连协议兼容。
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
    void requiredModeAllowsUnsignedNativeWebSocketWithoutOrigin() throws Exception {
        EdgeProxySignatureFilter filter = filter(EdgeProxyMode.REQUIRED);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/ws/voice");
        request.addHeader("X-Client-Platform", "ANDROID");
        request.addHeader("Upgrade", "websocket");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void requiredModeAllowsUnsignedNativeRequestWithoutOrigin() throws Exception {
        EdgeProxySignatureFilter filter = filter(EdgeProxyMode.REQUIRED);
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/auth/session/bootstrap");
        request.addHeader("X-Client-Platform", "ANDROID");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
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

    private static void addSignature(
            MockHttpServletRequest request,
            String externalHost) {
        String ray = "test-ray-ord";
        long timestamp = NOW.getEpochSecond();
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
}
