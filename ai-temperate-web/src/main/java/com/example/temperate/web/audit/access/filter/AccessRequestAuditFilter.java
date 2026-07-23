package com.example.temperate.web.audit.access.filter;

import com.example.temperate.service.audit.access.command.AccessAuditCommand;
import com.example.temperate.service.audit.access.service.AccessAuditEventService;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.web.auth.interceptor.AccessTokenAuthenticationInterceptor;
import com.example.temperate.web.auth.phonecountry.component.TrustedClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 在受 Access Token 保护的业务 API 外围采集请求完成事实，并将原始 IP 仅短暂传给脱敏服务。
 *
 * <p>过滤器不读取请求体、Cookie、Authorization、查询参数、邮箱、手机号或设备安装 ID；审计异常不会覆盖业务响应。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(prefix = "app.access-audit", name = "enabled", havingValue = "true")
public final class AccessRequestAuditFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-Trace-Id";
    private static final String UNMATCHED_ROUTE = "UNMATCHED_PROTECTED_ROUTE";

    private final AccessAuditEventService auditEventService;
    private final TrustedClientIpResolver clientIpResolver;

    public AccessRequestAuditFilter(
            AccessAuditEventService auditEventService,
            TrustedClientIpResolver clientIpResolver) {
        this.auditEventService = auditEventService;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/")
                || path.startsWith("/api/auth/")
                || path.equals("/api/health")
                || path.startsWith("/api/health/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        UUID traceId = UUID.randomUUID();
        response.setHeader(TRACE_HEADER, traceId.toString());
        long startedNanos = System.nanoTime();
        boolean failed = false;
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            failed = true;
            throw exception;
        } finally {
            response.setHeader(TRACE_HEADER, traceId.toString());
            int statusCode = failed && response.getStatus() < 400
                    ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                    : response.getStatus();
            AccessAuditCommand command = new AccessAuditCommand(
                    Instant.now(),
                    traceId,
                    principalUserId(request),
                    normalizeMethod(request.getMethod()),
                    routeTemplate(request),
                    statusCode,
                    Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L),
                    clientPlatform(request),
                    clientIpResolver.resolve(request).orElse(null));
            try {
                auditEventService.record(command);
            } catch (RuntimeException ignored) {
                // 双重失败开放保护：任何审计实现异常都不能改变原业务响应或吞掉原始业务异常。
            }
        }
    }

    private static Long principalUserId(HttpServletRequest request) {
        Object value = request.getAttribute(
                AccessTokenAuthenticationInterceptor.PRINCIPAL_ATTRIBUTE);
        return value instanceof SessionPrincipal principal ? principal.userId() : null;
    }

    private static String routeTemplate(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern == null) {
            return UNMATCHED_ROUTE;
        }
        String value = pattern.toString();
        return value.length() <= 255 ? value : value.substring(0, 255);
    }

    private static String normalizeMethod(String method) {
        String value = method == null ? "UNKNOWN" : method.toUpperCase(Locale.ROOT);
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    private static String clientPlatform(HttpServletRequest request) {
        return "ANDROID".equalsIgnoreCase(request.getHeader("X-Client-Platform"))
                ? "ANDROID" : "H5";
    }
}
