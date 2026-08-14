package com.example.temperate.web.apikey;

import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceLookupResult;
import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceSnapshot;
import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceSource;
import com.example.temperate.service.risk.ipintel.service.IpIntelligenceService;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.example.temperate.web.edgeproxy.TrustedEdgeNetworkContext;
import com.example.temperate.web.edgeproxy.TrustedEdgeNetworkContextResolver;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 该过滤器是来只使用 Worker 验签后的边缘 IP 查询权威风险分；客户端代理头、LOCAL/DEFAULT 降级分和无网络风险分值均不能放行。
 */
public final class ApiKeyIpRiskFilter extends OncePerRequestFilter {

    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(5);

    private final TrustedEdgeNetworkContextResolver edgeContextResolver;
    private final IpIntelligenceService ipIntelligenceService;
    private final ApiKeyProperties properties;
    private final OpenAiErrorResponseWriter errorWriter;
    private final MeterRegistry meterRegistry;

    public ApiKeyIpRiskFilter(
            TrustedEdgeNetworkContextResolver edgeContextResolver,
            IpIntelligenceService ipIntelligenceService,
            ApiKeyProperties properties,
            OpenAiErrorResponseWriter errorWriter,
            MeterRegistry meterRegistry) {
        this.edgeContextResolver = Objects.requireNonNull(edgeContextResolver);
        this.ipIntelligenceService = Objects.requireNonNull(ipIntelligenceService);
        this.properties = Objects.requireNonNull(properties);
        this.errorWriter = Objects.requireNonNull(errorWriter);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/v1/chat/completions".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        TrustedEdgeNetworkContext edgeContext = edgeContextResolver.resolve(request).orElse(null);
        if (edgeContext == null || edgeContext.clientIp() == null) {
            unavailable(response);
            return;
        }
        IpIntelligenceLookupResult result;
        try {
            result = ipIntelligenceService
                    .lookup(edgeContext.clientIp())
                    .block(LOOKUP_TIMEOUT);
        } catch (RuntimeException exception) {
            unavailable(response);
            return;
        }
        if (result == null || !authoritative(result.snapshot())) {
            unavailable(response);
            return;
        }
        if (result.snapshot().trustScore() < properties.getMinimumIpTrustScore()) {
            count("not_trusted");
            SecurityContextHolder.clearContext();
            errorWriter.write(
                    response,
                    HttpServletResponse.SC_FORBIDDEN,
                    "The client IP is not trusted.",
                    "permission_error",
                    "ip_not_trusted");
            return;
        }
        count("trusted");
        filterChain.doFilter(request, response);
    }

    private static boolean authoritative(IpIntelligenceSnapshot snapshot) {
        return snapshot != null
                && snapshot.scoreIncludesNetworkRisk()
                && snapshot.source() != IpIntelligenceSource.LOCAL_BIN
                && snapshot.source() != IpIntelligenceSource.DEFAULT;
    }

    private void unavailable(HttpServletResponse response) throws IOException {
        count("unavailable");
        SecurityContextHolder.clearContext();
        errorWriter.write(
                response,
                HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "IP risk intelligence is temporarily unavailable.",
                "server_error",
                "ip_risk_unavailable");
    }

    private void count(String result) {
        meterRegistry.counter("api.key.ip.gate", "result", result).increment();
    }
}
