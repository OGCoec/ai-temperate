package com.example.temperate.web.auth.diagnostic.filter;

import com.example.temperate.web.auth.phonecountry.component.TrustedClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 为认证 API 建立不读取凭据内容的端到端诊断关联边界。
 *
 * <p>过滤器为每个认证请求生成追踪标识，并记录入站 Cloudflare Ray、可选前端尝试标识、Cookie Header 字节数、
 * 地址解析是否分歧和请求耗时。它不记录 Cookie、Token、请求体、完整 IP 或转发头原文，也不改变认证结果。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class AuthRequestTraceFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String ATTEMPT_HEADER = "X-Turnstile-Attempt-Id";
    public static final String TRACE_ATTRIBUTE =
            AuthRequestTraceFilter.class.getName() + ".traceId";
    public static final String INBOUND_CF_RAY_ATTRIBUTE =
            AuthRequestTraceFilter.class.getName() + ".inboundCfRay";
    public static final String ATTEMPT_ATTRIBUTE =
            AuthRequestTraceFilter.class.getName() + ".attemptId";
    public static final String COOKIE_BYTES_ATTRIBUTE =
            AuthRequestTraceFilter.class.getName() + ".cookieHeaderBytes";

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthRequestTraceFilter.class);
    private static final String CF_RAY_HEADER = "CF-Ray";
    private static final String TRACE_MDC_KEY = "traceId";
    private static final String ATTEMPT_MDC_KEY = "turnstileAttemptId";
    private static final String CF_RAY_MDC_KEY = "inboundCfRay";

    private final TrustedClientIpResolver clientIpResolver;

    public AuthRequestTraceFilter(TrustedClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !(path.equals("/api/auth") || path.startsWith("/api/auth/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString();
        String inboundCfRay = safeBoundedIdentifier(request.getHeader(CF_RAY_HEADER), 128);
        String attemptId = safeAttemptId(request.getHeader(ATTEMPT_HEADER));
        int cookieHeaderBytes = utf8Length(request.getHeader("Cookie"));
        ClientAddressDiagnostic addressDiagnostic = clientAddressDiagnostic(request);
        request.setAttribute(TRACE_ATTRIBUTE, traceId);
        request.setAttribute(INBOUND_CF_RAY_ATTRIBUTE, inboundCfRay);
        request.setAttribute(ATTEMPT_ATTRIBUTE, attemptId);
        request.setAttribute(COOKIE_BYTES_ATTRIBUTE, cookieHeaderBytes);
        response.setHeader(TRACE_HEADER, traceId);

        String previousTraceId = MDC.get(TRACE_MDC_KEY);
        String previousAttemptId = MDC.get(ATTEMPT_MDC_KEY);
        String previousCfRay = MDC.get(CF_RAY_MDC_KEY);
        MDC.put(TRACE_MDC_KEY, traceId);
        MDC.put(ATTEMPT_MDC_KEY, attemptId);
        MDC.put(CF_RAY_MDC_KEY, inboundCfRay);
        long startedNanos = System.nanoTime();
        boolean failed = false;
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            failed = true;
            throw exception;
        } finally {
            response.setHeader(TRACE_HEADER, traceId);
            int status = failed && response.getStatus() < 400
                    ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                    : response.getStatus();
            LOGGER.info(
                    "auth_request_completed traceId={} turnstileAttemptId={} inboundCfRay={} "
                            + "method={} path={} status={} elapsedMs={} cookieHeaderBytes={} "
                            + "remoteAddressFamily={} resolvedAddressFamily={} "
                            + "resolvedClientDiffers={}",
                    traceId,
                    attemptId,
                    inboundCfRay,
                    safeMethod(request.getMethod()),
                    safePath(request.getRequestURI()),
                    status,
                    elapsedMillis(startedNanos),
                    addressDiagnostic.remoteFamily(),
                    addressDiagnostic.resolvedFamily(),
                    addressDiagnostic.differs());
            restoreMdc(TRACE_MDC_KEY, previousTraceId);
            restoreMdc(ATTEMPT_MDC_KEY, previousAttemptId);
            restoreMdc(CF_RAY_MDC_KEY, previousCfRay);
        }
    }

    private ClientAddressDiagnostic clientAddressDiagnostic(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        try {
            Optional<String> resolvedAddress = clientIpResolver.resolve(request);
            return new ClientAddressDiagnostic(
                    addressFamily(remoteAddress),
                    resolvedAddress.map(AuthRequestTraceFilter::addressFamily)
                            .orElse("ABSENT"),
                    resolvedAddress.isPresent()
                            && remoteAddress != null
                            && !resolvedAddress.get().equals(remoteAddress));
        } catch (RuntimeException ignored) {
            // 诊断解析失败不得改变认证请求；只记录无法解析，不记录异常消息或原始地址。
            return new ClientAddressDiagnostic(addressFamily(remoteAddress), "UNRESOLVED", false);
        }
    }

    private static int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String safeAttemptId(String value) {
        if (value == null || value.isBlank()) {
            return "absent";
        }
        String normalized = value.trim();
        return normalized.matches("^[A-Za-z0-9_-]{8,80}$") ? normalized : "invalid";
    }

    private static String safeBoundedIdentifier(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "absent";
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength
                        && normalized.matches("^[A-Za-z0-9-]+$")
                ? normalized
                : "invalid";
    }

    private static String safeMethod(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.matches("^[A-Z]{1,12}$") ? normalized : "INVALID";
    }

    private static String safePath(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = value.replace('\r', '_').replace('\n', '_');
        return normalized.length() <= 255 ? normalized : normalized.substring(0, 255);
    }

    private static String addressFamily(String value) {
        if (value == null || value.isBlank()) {
            return "ABSENT";
        }
        return value.indexOf(':') >= 0 ? "IPV6" : value.indexOf('.') >= 0 ? "IPV4" : "INVALID";
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static void restoreMdc(String key, String previousValue) {
        if (previousValue == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previousValue);
        }
    }

    private record ClientAddressDiagnostic(
            String remoteFamily, String resolvedFamily, boolean differs) {}
}
