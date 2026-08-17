package com.example.temperate.web.apikey;

import com.example.temperate.service.risk.config.NetworkRiskProperties;
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
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 该过滤器是来只使用 Worker 验签后的边缘 IP 查询权威风险分；客户端代理头、LOCAL/DEFAULT 降级分和无网络风险分值均不能放行。
 */
public final class ApiKeyIpRiskFilter extends OncePerRequestFilter {

    private final TrustedEdgeNetworkContextResolver edgeContextResolver;
    private final IpIntelligenceService ipIntelligenceService;
    private final ApiKeyProperties properties;
    private final NetworkRiskProperties networkRiskProperties;
    private final OpenAiErrorResponseWriter errorWriter;
    private final MeterRegistry meterRegistry;

    public ApiKeyIpRiskFilter(
            TrustedEdgeNetworkContextResolver edgeContextResolver,
            IpIntelligenceService ipIntelligenceService,
            ApiKeyProperties properties,
            NetworkRiskProperties networkRiskProperties,
            OpenAiErrorResponseWriter errorWriter,
            MeterRegistry meterRegistry) {
        this.edgeContextResolver = Objects.requireNonNull(edgeContextResolver);
        this.ipIntelligenceService = Objects.requireNonNull(ipIntelligenceService);
        this.properties = Objects.requireNonNull(properties);
        this.networkRiskProperties = Objects.requireNonNull(networkRiskProperties);
        this.errorWriter = Objects.requireNonNull(errorWriter);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ApiKeyV1Paths.isApiKeyEndpoint(request.getMethod(), request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        TrustedEdgeNetworkContext edgeContext = edgeContextResolver.resolve(request).orElse(null);
        if (edgeContext == null || edgeContext.clientIp() == null) {
            unavailable(response, "missing_edge_context", false);
            return;
        }
        IpIntelligenceLookupResult result;
        try {
            result = ipIntelligenceService
                    .lookup(edgeContext.clientIp())
                    .block(lookupWaitTimeout(networkRiskProperties));
        } catch (RuntimeException exception) {
            if (wasInterrupted(exception)) {
                // Servlet 容器已要求取消请求时恢复中断标记，避免后续线程复用时把取消信号吞掉。
                Thread.currentThread().interrupt();
            }
            unavailable(response, isTimeout(exception) ? "lookup_timeout" : "lookup_error", true);
            return;
        }
        if (result == null || !authoritative(result.snapshot())) {
            unavailable(response, "non_authoritative", true);
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

    static Duration lookupWaitTimeout(NetworkRiskProperties properties) {
        return Objects.requireNonNull(properties).apiKeyFilterWaitTimeout();
    }

    private static boolean isTimeout(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static boolean wasInterrupted(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    private void unavailable(
            HttpServletResponse response,
            String reason,
            boolean retryable) throws IOException {
        count("unavailable");
        meterRegistry.counter("api.key.ip.gate.unavailable", "reason", reason).increment();
        SecurityContextHolder.clearContext();
        if (retryable) {
            long seconds = Math.max(1L, networkRiskProperties.fallbackCacheTtl().toSeconds());
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        }
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
