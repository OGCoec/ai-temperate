package com.example.temperate.web.edgeproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.risk.security.NetworkRiskIdentifier;
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
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 验证 Cloudflare Worker 边缘签名绑定外部主机、请求边界、Ray 标识以及 v2 网络上下文。
 *
 * <p>测试使用固定假密钥和时钟，不包含任何生产 Secret，也不发起网络请求。</p>
 */
class EdgeProxySignatureVerifierTest {

    private static final Instant NOW = Instant.parse("2026-07-24T18:00:00Z");
    private static final byte[] SECRET =
            "edge-proxy-test-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    void acceptsCanonicalSignatureAndReturnsVerifiedExternalHost() {
        EdgeProxySignatureVerifier verifier = verifier();
        MockHttpServletRequest request = signedRequest(
                "POST",
                "/api/admin/auth/login/start",
                "attempt=1",
                "admin.niko000o.site",
                NOW.getEpochSecond(),
                "test-ray-ord");

        EdgeProxyVerificationResult result = verifier.verify(request);
        assertThat(result.externalHost()).isEqualTo("admin.niko000o.site");
        assertThat(result.protocolVersion()).isEqualTo("v2");
        assertThat(result.ray()).isEqualTo("test-ray-ord");
        assertThat(result.networkContext().clientIp()).isEqualTo("203.0.113.10");
        assertThat(result.networkContext().countryCode()).isEqualTo("US");
    }

    @Test
    void verifiesRawIpv6HeaderBeforeReturningCanonicalNetworkContext() {
        String rawIpv6 = "2001:0DB8:0000:0000:0000:0000:0000:0001";
        MockHttpServletRequest request = signedRequest(
                "POST",
                "/api/admin/auth/login/complete",
                null,
                "admin.niko000o.site",
                NOW.getEpochSecond(),
                "test-ray-ord",
                rawIpv6);

        EdgeProxyVerificationResult result = verifier().verify(request);
        NetworkRiskIdentifier riskIdentifier = new NetworkRiskIdentifier(
                new HmacSha256Identifier(SECRET));

        assertThat(result.networkContext().clientIp()).isEqualTo("2001:db8::1");
        assertThat(riskIdentifier.identifyIp(result.networkContext().clientIp()))
                .isEqualTo(riskIdentifier.identifyIp(rawIpv6));
    }

    @Test
    void acceptsV1OnlyDuringTheCompatibilityWindowWithoutNetworkContext() {
        EdgeProxySignatureVerifier verifier = verifier();
        String path = "/api/auth/csrf";
        String host = "niko000o.site";
        String ray = "test-ray-ord";
        long timestamp = NOW.getEpochSecond();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        request.addHeader(EdgeProxySignatureVerifier.VERSION_HEADER, "v1");
        request.addHeader(EdgeProxySignatureVerifier.EXTERNAL_HOST_HEADER, host);
        request.addHeader(
                EdgeProxySignatureVerifier.TIMESTAMP_HEADER,
                Long.toString(timestamp));
        request.addHeader(EdgeProxySignatureVerifier.RAY_HEADER, ray);
        request.addHeader(
                EdgeProxySignatureVerifier.SIGNATURE_HEADER,
                signV1("GET", path, host, timestamp, ray));

        EdgeProxyVerificationResult result = verifier.verify(request);

        assertThat(result.protocolVersion()).isEqualTo("v1");
        assertThat(result.ray()).isEqualTo("test-ray-ord");
        assertThat(result.optionalNetworkContext()).isEmpty();
    }

    @Test
    void rejectsSignatureWhenBoundRequestDataIsChanged() {
        EdgeProxySignatureVerifier verifier = verifier();
        MockHttpServletRequest pathChanged = signedRequest(
                "POST",
                "/api/auth/session/bootstrap",
                null,
                "niko000o.site",
                NOW.getEpochSecond(),
                "test-ray-ord");
        pathChanged.setRequestURI("/api/auth/session/logout");
        MockHttpServletRequest methodChanged = signedRequest(
                "GET",
                "/api/auth/csrf",
                null,
                "niko000o.site",
                NOW.getEpochSecond(),
                "test-ray-ord");
        methodChanged.setMethod("POST");
        MockHttpServletRequest queryChanged = signedRequest(
                "GET",
                "/api/auth/csrf",
                "attempt=1",
                "niko000o.site",
                NOW.getEpochSecond(),
                "test-ray-ord");
        queryChanged.setQueryString("attempt=2");

        assertThatThrownBy(() -> verifier.verify(pathChanged))
                .isInstanceOf(EdgeProxyVerificationException.class);
        assertThatThrownBy(() -> verifier.verify(methodChanged))
                .isInstanceOf(EdgeProxyVerificationException.class);
        assertThatThrownBy(() -> verifier.verify(queryChanged))
                .isInstanceOf(EdgeProxyVerificationException.class);
    }

    @Test
    void rejectsV2SignatureWhenAnyTrustedNetworkFieldIsChanged() {
        EdgeProxySignatureVerifier verifier = verifier();
        MockHttpServletRequest ipChanged = signedRequest(
                "GET",
                "/api/auth/csrf",
                null,
                "niko000o.site",
                NOW.getEpochSecond(),
                "test-ray-ord");
        ipChanged.removeHeader(EdgeProxySignatureVerifier.CLIENT_IP_HEADER);
        ipChanged.addHeader(EdgeProxySignatureVerifier.CLIENT_IP_HEADER, "203.0.113.11");
        MockHttpServletRequest countryChanged = signedRequest(
                "GET",
                "/api/auth/csrf",
                null,
                "niko000o.site",
                NOW.getEpochSecond(),
                "test-ray-ord");
        countryChanged.removeHeader(EdgeProxySignatureVerifier.COUNTRY_HEADER);
        countryChanged.addHeader(EdgeProxySignatureVerifier.COUNTRY_HEADER, "CA");
        MockHttpServletRequest coordinatesChanged = signedRequest(
                "GET",
                "/api/auth/csrf",
                null,
                "niko000o.site",
                NOW.getEpochSecond(),
                "test-ray-ord");
        coordinatesChanged.removeHeader(EdgeProxySignatureVerifier.LATITUDE_HEADER);
        coordinatesChanged.addHeader(EdgeProxySignatureVerifier.LATITUDE_HEADER, "43.6532");

        assertThatThrownBy(() -> verifier.verify(ipChanged))
                .isInstanceOf(EdgeProxyVerificationException.class);
        assertThatThrownBy(() -> verifier.verify(countryChanged))
                .isInstanceOf(EdgeProxyVerificationException.class);
        assertThatThrownBy(() -> verifier.verify(coordinatesChanged))
                .isInstanceOf(EdgeProxyVerificationException.class);
    }

    @Test
    void rejectsExpiredSignatureAndUnknownExternalHost() {
        EdgeProxySignatureVerifier verifier = verifier();
        MockHttpServletRequest expired = signedRequest(
                "GET",
                "/api/auth/csrf",
                null,
                "niko000o.site",
                NOW.minusSeconds(31).getEpochSecond(),
                "test-ray-ord");
        MockHttpServletRequest unknownHost = signedRequest(
                "GET",
                "/api/auth/csrf",
                null,
                "evil.example",
                NOW.getEpochSecond(),
                "test-ray-ord");

        assertThatThrownBy(() -> verifier.verify(expired))
                .isInstanceOf(EdgeProxyVerificationException.class);
        assertThatThrownBy(() -> verifier.verify(unknownHost))
                .isInstanceOf(EdgeProxyVerificationException.class);
    }

    private static EdgeProxySignatureVerifier verifier() {
        EdgeProxyProperties properties = new EdgeProxyProperties(
                EdgeProxyMode.REQUIRED,
                Base64.getEncoder().encodeToString(SECRET),
                Duration.ofSeconds(30));
        return new EdgeProxySignatureVerifier(
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static MockHttpServletRequest signedRequest(
            String method,
            String path,
            String query,
            String externalHost,
            long timestamp,
            String ray) {
        return signedRequest(
                method, path, query, externalHost, timestamp, ray, "203.0.113.10");
    }

    private static MockHttpServletRequest signedRequest(
            String method,
            String path,
            String query,
            String externalHost,
            long timestamp,
            String ray,
            String clientIp) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        request.setQueryString(query);
        request.addHeader(EdgeProxySignatureVerifier.VERSION_HEADER, "v2");
        request.addHeader(EdgeProxySignatureVerifier.EXTERNAL_HOST_HEADER, externalHost);
        request.addHeader(EdgeProxySignatureVerifier.TIMESTAMP_HEADER, Long.toString(timestamp));
        request.addHeader(EdgeProxySignatureVerifier.RAY_HEADER, ray);
        request.addHeader(EdgeProxySignatureVerifier.CLIENT_IP_HEADER, clientIp);
        request.addHeader(EdgeProxySignatureVerifier.COUNTRY_HEADER, "US");
        request.addHeader(EdgeProxySignatureVerifier.ASN_HEADER, "64500");
        request.addHeader(EdgeProxySignatureVerifier.LATITUDE_HEADER, "41.8781");
        request.addHeader(EdgeProxySignatureVerifier.LONGITUDE_HEADER, "-87.6298");
        request.addHeader(
                EdgeProxySignatureVerifier.SIGNATURE_HEADER,
                sign(method, path, query, externalHost, timestamp, ray, clientIp));
        return request;
    }

    private static String sign(
            String method,
            String path,
            String query,
            String externalHost,
            long timestamp,
            String ray) {
        return sign(
                method, path, query, externalHost, timestamp, ray, "203.0.113.10");
    }

    private static String sign(
            String method,
            String path,
            String query,
            String externalHost,
            long timestamp,
            String ray,
            String clientIp) {
        String pathAndQuery = query == null ? path : path + "?" + query;
        String canonical = String.join(
                "\n",
                "v2",
                method,
                pathAndQuery,
                externalHost,
                Long.toString(timestamp),
                ray,
                clientIp,
                "US",
                "64500",
                "41.8781",
                "-87.6298");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String signV1(
            String method,
            String path,
            String externalHost,
            long timestamp,
            String ray) {
        String canonical = String.join(
                "\n",
                "v1",
                method,
                path,
                externalHost,
                Long.toString(timestamp),
                ray);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
