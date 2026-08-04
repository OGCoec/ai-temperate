package com.example.temperate.web.audit.access.filter;

import com.example.temperate.service.audit.access.command.AccessAuditCommand;
import com.example.temperate.service.audit.access.service.AccessAuditEventService;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.web.auth.interceptor.UserSessionAuthenticationInterceptor;
import com.example.temperate.web.auth.phonecountry.component.TrustedClientIpResolver;
import com.example.temperate.web.user.aiconversation.diagnostic.AiConversationRequestTraceFilter;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 在受普通用户 Session 保护的业务 API 外围采集同步或异步请求的最终完成事实，并将原始 IP 仅短暂传给脱敏服务。
 *
 * <p>异步请求必须等待 Servlet 完成、错误或超时回调后才落审计；过滤器不读取请求体、Cookie、Authorization、
 * X-Refresh-Token、X-CSRF-Token、查询参数、邮箱、手机号或设备安装 ID，任何审计异常都不会覆盖业务响应。</p>
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
                || path.equals("/api/admin")
                || path.startsWith("/api/admin/")
                || path.equals("/api/health")
                || path.startsWith("/api/health/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        UUID traceId = existingTraceId(request);
        response.setHeader(TRACE_HEADER, traceId.toString());
        long startedNanos = System.nanoTime();
        Completion completion = new Completion(
                request, response, traceId, startedNanos);
        boolean failed = false;
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            failed = true;
            completion.record(true);
            throw exception;
        } finally {
            response.setHeader(TRACE_HEADER, traceId.toString());
            if (!failed && !completion.recorded()) {
                if (request.isAsyncStarted()) {
                    try {
                        request.getAsyncContext().addListener(completion);
                    } catch (IllegalStateException exception) {
                        // 异步上下文在监听器注册前结束时立即记录当前最终状态，避免整条请求缺少审计事实。
                        completion.record(false);
                    }
                } else {
                    completion.record(false);
                }
            }
        }
    }

    private static UUID existingTraceId(HttpServletRequest request) {
        Object value = request.getAttribute(
                AiConversationRequestTraceFilter.TRACE_ATTRIBUTE);
        if (value instanceof String traceId) {
            try {
                return UUID.fromString(traceId);
            } catch (IllegalArgumentException ignored) {
                // 非法请求属性不能覆盖访问审计自身的安全关联标识。
            }
        }
        return UUID.randomUUID();
    }

    private static Long principalUserId(HttpServletRequest request) {
        Object value = request.getAttribute(
                UserSessionAuthenticationInterceptor.PRINCIPAL_ATTRIBUTE);
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

    /**
     * 将同步返回和 Servlet 异步终态收敛为一次审计写入，避免 SSE 初始分派被误记为完整请求耗时。
     */
    private final class Completion implements AsyncListener {

        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final UUID traceId;
        private final long startedNanos;
        private final AtomicBoolean recorded = new AtomicBoolean();

        private Completion(
                HttpServletRequest request,
                HttpServletResponse response,
                UUID traceId,
                long startedNanos) {
            this.request = request;
            this.response = response;
            this.traceId = traceId;
            this.startedNanos = startedNanos;
        }

        private boolean recorded() {
            return recorded.get();
        }

        private void record(boolean failed) {
            if (!recorded.compareAndSet(false, true)) {
                return;
            }
            try {
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
                        Math.max(
                                0L,
                                (System.nanoTime() - startedNanos) / 1_000_000L),
                        clientPlatform(request),
                        clientIpResolver.resolve(request).orElse(null));
                auditEventService.record(command);
            } catch (RuntimeException ignored) {
                // IP 解析、命令构造或审计写入失败都必须开放，不能改变已经形成的业务响应。
            }
        }

        @Override
        public void onComplete(AsyncEvent event) {
            record(false);
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            record(true);
        }

        @Override
        public void onError(AsyncEvent event) {
            record(true);
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }
    }
}
