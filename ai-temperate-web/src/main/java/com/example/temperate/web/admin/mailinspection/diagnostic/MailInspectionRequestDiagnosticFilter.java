package com.example.temperate.web.admin.mailinspection.diagnostic;

import com.example.temperate.web.auth.diagnostic.filter.AuthRequestTraceFilter;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 记录邮件检查入口请求的模板化路由、异步完成状态和耗时，不记录真实 Job ID 或请求体。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class MailInspectionRequestDiagnosticFilter
        extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            MailInspectionRequestDiagnosticFilter.class);
    private static final String PREFIX =
            "/api/admin/mail-inspection/";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Completion completion = new Completion(request, response);
        boolean failed = false;
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            failed = true;
            completion.log(true);
            throw exception;
        } finally {
            if (!failed && !completion.logged()) {
                if (request.isAsyncStarted()) {
                    try {
                        request.getAsyncContext().addListener(completion);
                    } catch (IllegalStateException exception) {
                        completion.log(false);
                    }
                } else {
                    completion.log(false);
                }
            }
        }
    }

    static String routeTemplate(String path) {
        if (path == null) {
            return "/api/admin/mail-inspection";
        }
        // 无论 PathVariable 是否通过格式校验都隐藏任务段，避免恶意无效输入原样进入日志。
        return path.replaceFirst(
                "/jobs/[^/]+(?=/|$)",
                "/jobs/{jobId}");
    }

    /**
     * 在同步或异步结束路径中只执行一次日志写入，避免 SSE 完成与超时重复记录。
     */
    private static final class Completion implements AsyncListener {

        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final long startedNanos = System.nanoTime();
        private final AtomicBoolean logged = new AtomicBoolean();

        private Completion(
                HttpServletRequest request,
                HttpServletResponse response) {
            this.request = request;
            this.response = response;
        }

        private boolean logged() {
            return logged.get();
        }

        private void log(boolean failed) {
            if (!logged.compareAndSet(false, true)) {
                return;
            }
            LOGGER.info(
                    "event={} method={} route={} status={} outcome={} "
                            + "durationMs={} traceId={} cfRay={}",
                    "admin_mail_inspection_request_completed",
                    request.getMethod(),
                    routeTemplate(request.getRequestURI()),
                    response.getStatus(),
                    failed ? "failure" : "success",
                    TimeUnit.NANOSECONDS.toMillis(
                            Math.max(
                                    0L,
                                    System.nanoTime() - startedNanos)),
                    request.getAttribute(
                            AuthRequestTraceFilter.TRACE_ATTRIBUTE),
                    request.getAttribute(
                            AuthRequestTraceFilter
                                    .INBOUND_CF_RAY_ATTRIBUTE));
        }

        @Override
        public void onComplete(AsyncEvent event) {
            log(false);
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            log(true);
        }

        @Override
        public void onError(AsyncEvent event) {
            log(true);
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }
    }
}
