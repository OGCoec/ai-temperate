package com.example.temperate.web.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceLookupResult;
import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceSnapshot;
import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceSource;
import com.example.temperate.service.risk.ipintel.domain.NetworkType;
import com.example.temperate.service.risk.ipintel.service.IpIntelligenceService;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.example.temperate.web.edgeproxy.TrustedEdgeNetworkContext;
import com.example.temperate.web.edgeproxy.TrustedEdgeNetworkContextResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import reactor.core.publisher.Mono;

/**
 * 该测试是来约束 API Key IP 门禁只信任 Worker 验签属性，并在权威情报不可用时返回可重试的失败关闭响应。
 */
final class ApiKeyIpRiskFilterTest {

    @Test
    void trustScoreBelowSixtyIsRejectedAndSixtyIsAllowed() throws Exception {
        Invocation rejected = invoke(authoritative(59), true);
        Invocation allowed = invoke(authoritative(60), true);

        assertThat(rejected.response().getStatus()).isEqualTo(403);
        assertThat(rejected.response().getContentAsString()).contains("ip_not_trusted");
        assertThat(rejected.chainCalled()).isFalse();
        assertThat(allowed.response().getStatus()).isEqualTo(200);
        assertThat(allowed.chainCalled()).isTrue();
    }

    @Test
    void modelDiscoveryUsesTheSameIpRiskGateAsChatCompletions() throws Exception {
        Invocation allowed = invoke("GET", "/v1/models", authoritative(60), true);
        Invocation rejected = invoke("GET", "/v1/models", authoritative(59), true);

        assertThat(allowed.response().getStatus()).isEqualTo(200);
        assertThat(allowed.chainCalled()).isTrue();
        assertThat(rejected.response().getStatus()).isEqualTo(403);
        assertThat(rejected.chainCalled()).isFalse();
    }

    @Test
    void nonAuthoritativeScoreFailsClosed() throws Exception {
        IpIntelligenceSnapshot snapshot = new IpIntelligenceSnapshot(
                IpIntelligenceSnapshot.CURRENT_SCHEMA_VERSION,
                100,
                "US",
                64500L,
                null,
                null,
                NetworkType.RESIDENTIAL,
                false,
                IpIntelligenceSource.DEFAULT);

        Invocation result = invoke(snapshot, true);

        assertThat(result.response().getStatus()).isEqualTo(503);
        assertThat(result.response().getContentAsString()).contains("ip_risk_unavailable");
        assertThat(result.response().getHeader("Retry-After")).isEqualTo("30");
        assertThat(result.registry().counter(
                        "api.key.ip.gate.unavailable", "reason", "non_authoritative")
                .count()).isOne();
        assertThat(result.chainCalled()).isFalse();
    }

    @Test
    void forgedForwardedHeaderCannotReplaceMissingVerifiedEdgeAttribute() throws Exception {
        Invocation result = invoke(authoritative(100), false);

        assertThat(result.response().getStatus()).isEqualTo(503);
        assertThat(result.response().getHeader("Retry-After")).isNull();
        assertThat(result.registry().counter(
                        "api.key.ip.gate.unavailable", "reason", "missing_edge_context")
                .count()).isOne();
        assertThat(result.chainCalled()).isFalse();
    }

    @Test
    void lookupFailureReturnsRetryableServiceUnavailable() throws Exception {
        IpIntelligenceService service = ignored -> Mono.error(
                new IllegalStateException("provider temporarily unavailable"));

        Invocation result = invoke(service, true);

        assertThat(result.response().getStatus()).isEqualTo(503);
        assertThat(result.response().getHeader("Retry-After")).isEqualTo("30");
        assertThat(result.registry().counter(
                        "api.key.ip.gate.unavailable", "reason", "lookup_error")
                .count()).isOne();
        assertThat(result.chainCalled()).isFalse();
    }

    @Test
    void lookupTimeoutReturnsRetryableServiceUnavailableWithTimeoutReason() throws Exception {
        IpIntelligenceService service = ignored -> Mono.never();

        Invocation result = invoke(
                service,
                true,
                networkRiskProperties(Duration.ofMillis(100)));

        assertThat(result.response().getStatus()).isEqualTo(503);
        assertThat(result.response().getHeader("Retry-After")).isEqualTo("30");
        assertThat(result.registry().counter(
                        "api.key.ip.gate.unavailable", "reason", "lookup_timeout")
                .count()).isOne();
        assertThat(result.chainCalled()).isFalse();
    }

    @Test
    void lookupWaitTimeoutUsesSharedEightSecondBudgetAndCompletionMargin() {
        assertThat(ApiKeyIpRiskFilter.lookupWaitTimeout(networkRiskProperties()))
                .isEqualTo(Duration.ofMillis(8500));
    }

    private static Invocation invoke(
            IpIntelligenceSnapshot snapshot,
            boolean verifiedEdgeAttribute) throws Exception {
        IpIntelligenceService service = ip -> Mono.just(
                new IpIntelligenceLookupResult(snapshot, false));
        return invoke(service, verifiedEdgeAttribute);
    }

    private static Invocation invoke(
            IpIntelligenceService service,
            boolean verifiedEdgeAttribute) throws Exception {
        return invoke(service, verifiedEdgeAttribute, networkRiskProperties());
    }

    private static Invocation invoke(
            String method,
            String path,
            IpIntelligenceSnapshot snapshot,
            boolean verifiedEdgeAttribute) throws Exception {
        IpIntelligenceService service = ip -> Mono.just(
                new IpIntelligenceLookupResult(snapshot, false));
        return invoke(service, verifiedEdgeAttribute, networkRiskProperties(), method, path);
    }

    private static Invocation invoke(
            IpIntelligenceService service,
            boolean verifiedEdgeAttribute,
            NetworkRiskProperties networkRiskProperties) throws Exception {
        return invoke(service, verifiedEdgeAttribute, networkRiskProperties,
                "POST", "/v1/chat/completions");
    }

    private static Invocation invoke(
            IpIntelligenceService service,
            boolean verifiedEdgeAttribute,
            NetworkRiskProperties networkRiskProperties,
            String method,
            String path) throws Exception {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.setMinimumIpTrustScore(60);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ApiKeyIpRiskFilter filter = new ApiKeyIpRiskFilter(
                new TrustedEdgeNetworkContextResolver(),
                service,
                properties,
                networkRiskProperties,
                new OpenAiErrorResponseWriter(new ObjectMapper()),
                registry);
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("X-Forwarded-For", "198.51.100.200");
        if (verifiedEdgeAttribute) {
            request.setAttribute(
                    TrustedEdgeNetworkContextResolver.VERIFIED_NETWORK_CONTEXT_ATTRIBUTE,
                    new TrustedEdgeNetworkContext(
                            "203.0.113.10", "US", 64500L, null, null, "test-ray"));
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();
        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                chainCalled.set(true));
        return new Invocation(response, chainCalled.get(), registry);
    }

    private static NetworkRiskProperties networkRiskProperties() {
        return networkRiskProperties(Duration.ofSeconds(8));
    }

    private static NetworkRiskProperties networkRiskProperties(Duration lookupTimeout) {
        String secret = Base64.getEncoder().encodeToString(
                "network-risk-filter-test-secret-0123456789".getBytes());
        return new NetworkRiskProperties(
                NetworkRiskMode.ENFORCE,
                secret,
                secret,
                URI.create("https://api.ip2location.test/"),
                URI.create("https://api.iping.test/"),
                true,
                lookupTimeout,
                Duration.ofHours(6),
                Duration.ofSeconds(30),
                Duration.ofSeconds(9),
                32,
                Duration.ofMinutes(30),
                Duration.ofHours(6),
                Duration.ofMinutes(5),
                Duration.ofMinutes(30),
                200D,
                Duration.ofHours(24),
                Duration.ofMinutes(10),
                new NetworkRiskProperties.WebRtc(
                        Duration.ofSeconds(8),
                        Duration.ofSeconds(12),
                        Duration.ofSeconds(3),
                        List.of(
                                URI.create("stun:stun.l.google.com:19302"),
                                URI.create("stun:stun.cloudflare.com:3478"),
                                URI.create("stun:global.stun.twilio.com:3478"),
                                URI.create("stun:stun.nextcloud.com:3478")),
                        8,
                        secret));
    }

    private static IpIntelligenceSnapshot authoritative(int score) {
        return new IpIntelligenceSnapshot(
                IpIntelligenceSnapshot.CURRENT_SCHEMA_VERSION,
                score,
                "US",
                64500L,
                null,
                null,
                NetworkType.RESIDENTIAL,
                true,
                IpIntelligenceSource.IP2LOCATION);
    }

    private record Invocation(
            MockHttpServletResponse response,
            boolean chainCalled,
            SimpleMeterRegistry registry) {
    }
}
