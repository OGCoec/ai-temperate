package com.example.temperate.web.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.web.auth.phonecountry.component.TrustedClientIpResolver;
import com.example.temperate.web.edgeproxy.EdgeProxySignatureVerifier;
import com.example.temperate.web.edgeproxy.TrustedEdgeNetworkContext;
import com.example.temperate.web.edgeproxy.TrustedEdgeNetworkContextResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 验证请求网络上下文只采用已验签的 Worker 属性，并为无 Origin 的 Android 直连保留受控降级。
 */
class RiskRequestContextResolverTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-07-26T00:00:00Z");
    private static final String VERIFIED_CLIENT_IP = "130.131.4.13";
    private static final String WORKER_SUBREQUEST_IP = "2a06:98c0:3600::103";

    @Test
    void verifiedEdgeContextWinsOverWorkerSubrequestHeaders() {
        TrustedClientIpResolver directResolver = mock(TrustedClientIpResolver.class);
        RiskRequestContextResolver resolver = resolver(directResolver);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Origin", "https://niko000o.site");
        request.addHeader("CF-Connecting-IP", WORKER_SUBREQUEST_IP);
        request.addHeader(EdgeProxySignatureVerifier.CLIENT_IP_HEADER, "1.1.1.1");
        request.setAttribute(
                TrustedEdgeNetworkContextResolver.VERIFIED_NETWORK_CONTEXT_ATTRIBUTE,
                new TrustedEdgeNetworkContext(
                        VERIFIED_CLIENT_IP,
                        "US",
                        12345L,
                        null,
                        null,
                        "test-ray"));

        Optional<TrustedNetworkObservation> result = resolver.resolve(request);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().clientIp()).isEqualTo(VERIFIED_CLIENT_IP);
        assertThat(result.orElseThrow().countryCode()).isEqualTo("US");
        assertThat(result.orElseThrow().observedAt()).isEqualTo(OBSERVED_AT);
        verifyNoInteractions(directResolver);
    }

    @Test
    void browserWithoutVerifiedContextDoesNotTrustIpHeaders() {
        TrustedClientIpResolver directResolver = mock(TrustedClientIpResolver.class);
        RiskRequestContextResolver resolver = resolver(directResolver);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Origin", "https://niko000o.site");
        request.addHeader("CF-Connecting-IP", WORKER_SUBREQUEST_IP);
        request.addHeader(EdgeProxySignatureVerifier.CLIENT_IP_HEADER, VERIFIED_CLIENT_IP);

        assertThat(resolver.resolve(request)).isEmpty();
        verifyNoInteractions(directResolver);
    }

    @Test
    void originlessAndroidRequestCanUseTrustedDirectResolver() {
        TrustedClientIpResolver directResolver = mock(TrustedClientIpResolver.class);
        RiskRequestContextResolver resolver = resolver(directResolver);
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(directResolver.resolve(request)).thenReturn(Optional.of("8.8.8.8"));

        Optional<TrustedNetworkObservation> result = resolver.resolve(request);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().clientIp()).isEqualTo("8.8.8.8");
        assertThat(result.orElseThrow().countryCode()).isNull();
        assertThat(result.orElseThrow().observedAt()).isEqualTo(OBSERVED_AT);
    }

    private static RiskRequestContextResolver resolver(
            TrustedClientIpResolver directResolver) {
        return new RiskRequestContextResolver(
                new TrustedEdgeNetworkContextResolver(),
                directResolver,
                Clock.fixed(OBSERVED_AT, ZoneOffset.UTC));
    }
}
