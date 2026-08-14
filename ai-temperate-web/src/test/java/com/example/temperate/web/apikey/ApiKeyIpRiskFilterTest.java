package com.example.temperate.web.apikey;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import reactor.core.publisher.Mono;

/**
 * 该测试是来约束 API Key IP 门禁只信任 Worker 验签属性，59 分拒绝、60 分放行，非权威或缺失情报统一失败关闭为 503。
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
        assertThat(result.chainCalled()).isFalse();
    }

    @Test
    void forgedForwardedHeaderCannotReplaceMissingVerifiedEdgeAttribute() throws Exception {
        Invocation result = invoke(authoritative(100), false);

        assertThat(result.response().getStatus()).isEqualTo(503);
        assertThat(result.chainCalled()).isFalse();
    }

    private static Invocation invoke(
            IpIntelligenceSnapshot snapshot,
            boolean verifiedEdgeAttribute) throws Exception {
        IpIntelligenceService service = ip -> Mono.just(
                new IpIntelligenceLookupResult(snapshot, false));
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.setMinimumIpTrustScore(60);
        ApiKeyIpRiskFilter filter = new ApiKeyIpRiskFilter(
                new TrustedEdgeNetworkContextResolver(),
                service,
                properties,
                new OpenAiErrorResponseWriter(new ObjectMapper()),
                new SimpleMeterRegistry());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/v1/chat/completions");
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
        return new Invocation(response, chainCalled.get());
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
            boolean chainCalled) {
    }
}
