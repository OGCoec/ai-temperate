package com.example.temperate.web.apiresponse.diagnostic;

import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseDiagnosticClock;
import com.example.temperate.web.apiresponse.ApiResponsesTraceFilter;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 该过滤器是来观察 `/v1/responses` 的 Servlet REQUEST、ASYNC 与 ERROR 生命周期，并与 AOP/Gate 日志复用同一个 Trace ID。
 * 它不生成 Trace、不读取请求体、Authorization、Cookie 或响应正文，也不参与认证、异常映射和流式响应转换。
 */
public final class ApiResponsesStreamDiagnosticFilter extends OncePerRequestFilter {

    private static final String DIAGNOSTIC_SCHEMA = "responses-diag-v1";
    private static final String STATE_ATTRIBUTE =
            ApiResponsesStreamDiagnosticFilter.class.getName() + ".state";
    private static final Logger LOGGER = LoggerFactory.getLogger(
            ApiResponsesStreamDiagnosticFilter.class);

    private final ApiKeyProperties properties;
    private final ApiResponseDiagnosticClock clock;

    public ApiResponsesStreamDiagnosticFilter(
            ApiKeyProperties properties,
            ApiResponseDiagnosticClock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.getStreamDiagnostics().isEnabled()
                || !"POST".equalsIgnoreCase(request.getMethod())
                || !"/v1/responses".equals(request.getRequestURI());
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        RequestState state = state(request, response);
        DispatcherType dispatcher = request.getDispatcherType();
        int dispatchOrdinal = state.increment(dispatcher);
        if (state.sampled) {
            state.dispatchLogged.set(true);
            logDispatch(
                    state,
                    dispatcher,
                    dispatchOrdinal,
                    request.isAsyncStarted(),
                    response.getStatus());
        }
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException failure) {
            state.failureType = safeType(failure);
            state.rootFailureType = safeRootCauseType(failure);
            ensureInitialDispatchLogged(state);
            String template = "event=api_responses_servlet_dispatch_error diagnosticSchema={} traceId={} protocol=responses mode={} dispatcher={} failureType={} rootFailureType={} asyncStarted={} committed={} status={}";
            Object[] arguments = {
                DIAGNOSTIC_SCHEMA,
                state.traceId,
                responseMode(response),
                dispatcher,
                state.failureType,
                state.rootFailureType,
                request.isAsyncStarted(),
                response.isCommitted(),
                response.getStatus()
            };
            if (response.isCommitted()) {
                safeError(template, arguments);
            } else {
                safeWarn(template, arguments);
            }
            if (!request.isAsyncStarted()) {
                complete(state, response, "ERROR");
            }
            throw failure;
        } finally {
            if (request.isAsyncStarted()) {
                attachListener(request, state, response);
            } else {
                complete(
                        state,
                        response,
                        dispatcher == DispatcherType.ERROR
                                ? "ERROR_DISPATCH" : "COMPLETE");
            }
        }
    }

    private RequestState state(
            HttpServletRequest request,
            HttpServletResponse response) {
        Object existing = request.getAttribute(STATE_ATTRIBUTE);
        if (existing instanceof RequestState state) {
            return state;
        }
        double sampleRate = properties.getStreamDiagnostics().getSampleRate();
        boolean sampled = sampleRate > 0.0d
                && ThreadLocalRandom.current().nextDouble()
                        < Math.min(1.0d, sampleRate);
        String traceId = safeTraceId(response.getHeader(
                ApiResponsesTraceFilter.TRACE_HEADER));
        if ("absent".equals(traceId)) {
            traceId = safeTraceId(MDC.get(ApiResponsesTraceFilter.TRACE_MDC_KEY));
        }
        RequestState created = new RequestState(
                traceId,
                clock.nanoTime(),
                sampled,
                request.getDispatcherType(),
                request.isAsyncStarted(),
                response.getStatus(),
                safeContentType(request.getContentType()),
                acceptClass(request));
        request.setAttribute(STATE_ATTRIBUTE, created);
        return created;
    }

    private void attachListener(
            HttpServletRequest request,
            RequestState state,
            HttpServletResponse response) {
        if (state.listenerAttached.compareAndSet(false, true)) {
            request.getAsyncContext().addListener(
                    new CompletionListener(state, response));
            if (state.sampled) {
                safeInfo(
                        "event=api_responses_servlet_dispatch diagnosticSchema={} traceId={} protocol=responses mode={} dispatcher=ASYNC_STARTED timeoutMs={}",
                        DIAGNOSTIC_SCHEMA,
                        state.traceId,
                        responseMode(response),
                        request.getAsyncContext().getTimeout());
            }
        }
    }

    private void complete(
            RequestState state,
            HttpServletResponse response,
            String outcome) {
        if (!state.completed.compareAndSet(false, true)) {
            return;
        }
        boolean failed = response.getStatus() >= 400
                || !"none".equals(state.failureType)
                || outcome.contains("ERROR")
                || outcome.contains("TIMEOUT");
        if (!state.sampled && !failed) {
            return;
        }
        ensureInitialDispatchLogged(state);
        safeInfo(
                "event=api_responses_servlet_complete diagnosticSchema={} traceId={} protocol=responses mode={} outcome={} elapsedMs={} status={} responseContentType={} committed={} requestDispatches={} asyncDispatches={} errorDispatches={} failureType={} rootFailureType={}",
                DIAGNOSTIC_SCHEMA,
                state.traceId,
                responseMode(response),
                outcome,
                Duration.ofNanos(Math.max(0L, clock.nanoTime() - state.startedNanos))
                        .toMillis(),
                response.getStatus(),
                safeContentType(response.getContentType()),
                response.isCommitted(),
                state.requestDispatches.get(),
                state.asyncDispatches.get(),
                state.errorDispatches.get(),
                state.failureType,
                state.rootFailureType);
    }

    private static void logDispatch(
            RequestState state,
            DispatcherType dispatcher,
            int dispatchOrdinal,
            boolean asyncStarted,
            int status) {
        safeInfo(
                "event=api_responses_servlet_dispatch diagnosticSchema={} traceId={} protocol=responses mode=unknown dispatcher={} dispatchOrdinal={} asyncStarted={} status={} requestContentType={} acceptHeaderClass={}",
                DIAGNOSTIC_SCHEMA,
                state.traceId,
                dispatcher,
                dispatchOrdinal,
                asyncStarted,
                status,
                state.requestContentType,
                state.acceptHeaderClass);
    }

    private static void ensureInitialDispatchLogged(RequestState state) {
        if (!state.dispatchLogged.compareAndSet(false, true)) {
            return;
        }
        logDispatch(
                state,
                state.initialDispatcher,
                1,
                state.initialAsyncStarted,
                state.initialStatus);
    }

    private final class CompletionListener implements AsyncListener {
        private final RequestState state;
        private final HttpServletResponse response;

        private CompletionListener(
                RequestState state,
                HttpServletResponse response) {
            this.state = state;
            this.response = response;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            complete(state, response, "ASYNC_COMPLETE");
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            recordAsyncFailure(event, "ASYNC_TIMEOUT");
        }

        @Override
        public void onError(AsyncEvent event) {
            recordAsyncFailure(event, "ASYNC_ERROR");
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            if (state.sampled) {
                safeInfo(
                        "event=api_responses_servlet_dispatch diagnosticSchema={} traceId={} protocol=responses mode={} dispatcher=ASYNC_RESTART",
                        DIAGNOSTIC_SCHEMA,
                        state.traceId,
                        responseMode(response));
            }
            event.getAsyncContext().addListener(this);
        }

        private void recordAsyncFailure(AsyncEvent event, String outcome) {
            state.failureType = safeType(event.getThrowable());
            state.rootFailureType = safeRootCauseType(event.getThrowable());
            ensureInitialDispatchLogged(state);
            safeWarn(
                    "event=api_responses_servlet_dispatch_error diagnosticSchema={} traceId={} protocol=responses mode={} dispatcher={} failureType={} rootFailureType={} committed={} status={}",
                    DIAGNOSTIC_SCHEMA,
                    state.traceId,
                    responseMode(response),
                    outcome,
                    state.failureType,
                    state.rootFailureType,
                    response.isCommitted(),
                    response.getStatus());
            complete(state, response, outcome);
        }
    }

    private static final class RequestState {
        private final String traceId;
        private final long startedNanos;
        private final boolean sampled;
        private final DispatcherType initialDispatcher;
        private final boolean initialAsyncStarted;
        private final int initialStatus;
        private final String requestContentType;
        private final AcceptHeaderClass acceptHeaderClass;
        private final AtomicBoolean listenerAttached = new AtomicBoolean();
        private final AtomicBoolean dispatchLogged = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicInteger requestDispatches = new AtomicInteger();
        private final AtomicInteger asyncDispatches = new AtomicInteger();
        private final AtomicInteger errorDispatches = new AtomicInteger();
        private volatile String failureType = "none";
        private volatile String rootFailureType = "none";

        private RequestState(
                String traceId,
                long startedNanos,
                boolean sampled,
                DispatcherType initialDispatcher,
                boolean initialAsyncStarted,
                int initialStatus,
                String requestContentType,
                AcceptHeaderClass acceptHeaderClass) {
            this.traceId = traceId;
            this.startedNanos = startedNanos;
            this.sampled = sampled;
            this.initialDispatcher = initialDispatcher;
            this.initialAsyncStarted = initialAsyncStarted;
            this.initialStatus = initialStatus;
            this.requestContentType = requestContentType;
            this.acceptHeaderClass = acceptHeaderClass;
        }

        private int increment(DispatcherType dispatcherType) {
            return switch (dispatcherType) {
                case ASYNC -> asyncDispatches.incrementAndGet();
                case ERROR -> errorDispatches.incrementAndGet();
                default -> requestDispatches.incrementAndGet();
            };
        }
    }

    private enum AcceptHeaderClass {
        SSE_ONLY,
        JSON_ONLY,
        SSE_AND_JSON,
        WILDCARD,
        OTHER,
        ABSENT
    }

    private static AcceptHeaderClass acceptClass(HttpServletRequest request) {
        String value = request.getHeader("Accept");
        if (value == null || value.isBlank()) {
            return AcceptHeaderClass.ABSENT;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        boolean sse = normalized.contains("text/event-stream");
        boolean json = normalized.contains("application/json");
        if (sse && json) {
            return AcceptHeaderClass.SSE_AND_JSON;
        }
        if (sse) {
            return AcceptHeaderClass.SSE_ONLY;
        }
        if (json) {
            return AcceptHeaderClass.JSON_ONLY;
        }
        if (normalized.contains("*/*")) {
            return AcceptHeaderClass.WILDCARD;
        }
        return AcceptHeaderClass.OTHER;
    }

    private static String responseMode(HttpServletResponse response) {
        String contentType = safeContentType(response.getContentType());
        if ("text/event-stream".equals(contentType)) {
            return "sse";
        }
        if ("application/json".equals(contentType)) {
            return "json";
        }
        return "unknown";
    }

    private static String safeTraceId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,128}")
                ? value : "absent";
    }

    private static String safeType(Throwable failure) {
        String name = failure == null ? "none" : failure.getClass().getName();
        return name.matches("[A-Za-z0-9_.$]{1,200}") ? name : "unknown";
    }

    private static String safeRootCauseType(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && current.getCause() != null && depth++ < 16) {
            current = current.getCause();
        }
        return safeType(current);
    }

    private static String safeContentType(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = value.split(";", 2)[0]
                .trim()
                .toLowerCase(Locale.ROOT);
        return normalized.length() <= 96
                && normalized.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")
                ? normalized : "unknown";
    }

    private static void safeInfo(String template, Object... arguments) {
        try {
            LOGGER.info(template, arguments);
        } catch (RuntimeException ignored) {
            // Servlet 诊断后端异常不能改变分派、异步生命周期或最终 HTTP 响应。
        }
    }

    private static void safeWarn(String template, Object... arguments) {
        try {
            LOGGER.warn(template, arguments);
        } catch (RuntimeException ignored) {
            // 失败日志异常不能替换正在传播的业务或 Servlet 异常。
        }
    }

    private static void safeError(String template, Object... arguments) {
        try {
            LOGGER.error(template, arguments);
        } catch (RuntimeException ignored) {
            // 已提交响应后的错误诊断失败时仍必须保留原 Servlet 异常行为。
        }
    }
}
