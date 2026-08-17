package com.example.temperate.web.apichat.diagnostic;

import com.example.temperate.service.user.apichat.diagnostic.ApiChatDiagnosticClock;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 该过滤器是来观察 `/v1/chat/completions` 的 Servlet REQUEST、ASYNC 与 ERROR 生命周期，
 * 用于识别流内失败之后的异步重派发和客户端断开；它不读取正文、不记录 Authorization，也不改变安全链判定。
 */
public final class ApiChatStreamDiagnosticFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String TRACE_MDC_KEY = "apiChatTraceId";
    private static final String STATE_ATTRIBUTE =
            ApiChatStreamDiagnosticFilter.class.getName() + ".state";
    private static final Logger LOGGER = LoggerFactory.getLogger(
            ApiChatStreamDiagnosticFilter.class);

    private final ApiKeyProperties properties;
    private final ApiChatDiagnosticClock clock;

    public ApiChatStreamDiagnosticFilter(
            ApiKeyProperties properties,
            ApiChatDiagnosticClock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.getStreamDiagnostics().isEnabled()
                || !"POST".equalsIgnoreCase(request.getMethod())
                || !"/v1/chat/completions".equals(request.getRequestURI());
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
        response.setHeader(TRACE_HEADER, state.traceId);
        String previousTrace = MDC.get(TRACE_MDC_KEY);
        MDC.put(TRACE_MDC_KEY, state.traceId);
        DispatcherType dispatcher = request.getDispatcherType();
        int dispatchOrdinal = state.increment(dispatcher);
        if (state.sampled) {
            state.dispatchLogged.set(true);
            logDispatch(
                    state,
                    dispatcher,
                    dispatchOrdinal,
                    request.isAsyncStarted(),
                    response.getStatus(),
                    safeContentType(request.getContentType()),
                    acceptsSse(request),
                    acceptsJson(request),
                    acceptClass(request));
        }
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException failure) {
            state.failureType = safeType(failure);
            state.failureCauseType = safeRootCauseType(failure);
            ensureInitialDispatchLogged(state);
            safeWarn(
                    "event=api_chat_servlet_dispatch_error traceId={} dispatcher={} failureType={} causeType={} asyncStarted={} committed={} status={}",
                    state.traceId,
                    dispatcher,
                    state.failureType,
                    state.failureCauseType,
                    request.isAsyncStarted(),
                    response.isCommitted(),
                    response.getStatus());
            if (!request.isAsyncStarted()) {
                complete(state, response, "ERROR");
            }
            throw failure;
        } finally {
            if (request.isAsyncStarted()) {
                attachListener(request, state, response);
            } else {
                complete(state, response,
                        dispatcher == DispatcherType.ERROR ? "ERROR_DISPATCH" : "COMPLETE");
            }
            restoreMdc(previousTrace);
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
                && ThreadLocalRandom.current().nextDouble() < Math.min(1.0d, sampleRate);
        RequestState created = new RequestState(
                UUID.randomUUID().toString(),
                clock.nanoTime(),
                sampled,
                request.getDispatcherType(),
                request.isAsyncStarted(),
                response.getStatus(),
                safeContentType(request.getContentType()),
                acceptsSse(request),
                acceptsJson(request),
                acceptClass(request));
        request.setAttribute(STATE_ATTRIBUTE, created);
        return created;
    }

    private void attachListener(
            HttpServletRequest request,
            RequestState state,
            HttpServletResponse response) {
        if (state.listenerAttached.compareAndSet(false, true)) {
            request.getAsyncContext().addListener(new CompletionListener(state, response));
            if (state.sampled) {
                safeInfo(
                        "event=api_chat_servlet_async_started traceId={} timeoutMs={}",
                        state.traceId,
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
                "event=api_chat_servlet_complete diagnosticSchema=chat-diag-v1 traceId={} outcome={} elapsedMs={} status={} responseContentType={} committed={} requestDispatches={} asyncDispatches={} errorDispatches={} failureType={} causeType={}",
                state.traceId,
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
                state.failureCauseType);
    }

    private static void logDispatch(
            RequestState state,
            DispatcherType dispatcher,
            int dispatchOrdinal,
            boolean asyncStarted,
            int status,
            String requestContentType,
            boolean requestAcceptsSse,
            boolean requestAcceptsJson,
            AcceptHeaderClass requestAcceptClass) {
        safeInfo(
                "event=api_chat_servlet_dispatch diagnosticSchema=chat-diag-v1 traceId={} dispatcher={} dispatchOrdinal={} asyncStarted={} status={} requestContentType={} acceptsSse={} acceptsJson={} acceptHeaderClass={}",
                state.traceId,
                dispatcher,
                dispatchOrdinal,
                asyncStarted,
                status,
                requestContentType,
                requestAcceptsSse,
                requestAcceptsJson,
                requestAcceptClass);
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
                state.initialStatus,
                state.requestContentType,
                state.acceptsSse,
                state.acceptsJson,
                state.acceptHeaderClass);
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
                .toLowerCase(java.util.Locale.ROOT);
        return normalized.length() <= 96
                && normalized.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")
                ? normalized : "unknown";
    }

    private static boolean acceptsSse(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.toLowerCase(java.util.Locale.ROOT)
                .contains("text/event-stream");
    }

    private static boolean acceptsJson(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.toLowerCase(java.util.Locale.ROOT)
                .contains("application/json");
    }

    private static AcceptHeaderClass acceptClass(HttpServletRequest request) {
        String value = request.getHeader("Accept");
        if (value == null || value.isBlank()) {
            return AcceptHeaderClass.ABSENT;
        }
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
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

    private static void restoreMdc(String previousTrace) {
        if (previousTrace == null) {
            MDC.remove(TRACE_MDC_KEY);
        } else {
            MDC.put(TRACE_MDC_KEY, previousTrace);
        }
    }

    private final class CompletionListener implements AsyncListener {
        private final RequestState state;
        private final HttpServletResponse response;

        private CompletionListener(RequestState state, HttpServletResponse response) {
            this.state = state;
            this.response = response;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            complete(state, response, "ASYNC_COMPLETE");
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            state.failureType = safeType(event.getThrowable());
            state.failureCauseType = safeRootCauseType(event.getThrowable());
            ensureInitialDispatchLogged(state);
            safeWarn(
                    "event=api_chat_servlet_async_timeout traceId={} failureType={} causeType={}",
                    state.traceId,
                    state.failureType,
                    state.failureCauseType);
            complete(state, response, "ASYNC_TIMEOUT");
        }

        @Override
        public void onError(AsyncEvent event) {
            state.failureType = safeType(event.getThrowable());
            state.failureCauseType = safeRootCauseType(event.getThrowable());
            ensureInitialDispatchLogged(state);
            safeWarn(
                    "event=api_chat_servlet_async_error traceId={} failureType={} causeType={}",
                    state.traceId,
                    state.failureType,
                    state.failureCauseType);
            complete(state, response, "ASYNC_ERROR");
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            if (state.sampled) {
                safeInfo("event=api_chat_servlet_async_restart traceId={}", state.traceId);
            }
            event.getAsyncContext().addListener(this);
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
        private final boolean acceptsSse;
        private final boolean acceptsJson;
        private final AcceptHeaderClass acceptHeaderClass;
        private final AtomicBoolean listenerAttached = new AtomicBoolean();
        private final AtomicBoolean dispatchLogged = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicInteger requestDispatches = new AtomicInteger();
        private final AtomicInteger asyncDispatches = new AtomicInteger();
        private final AtomicInteger errorDispatches = new AtomicInteger();
        private volatile String failureType = "none";
        private volatile String failureCauseType = "none";

        private RequestState(
                String traceId,
                long startedNanos,
                boolean sampled,
                DispatcherType initialDispatcher,
                boolean initialAsyncStarted,
                int initialStatus,
                String requestContentType,
                boolean acceptsSse,
                boolean acceptsJson,
                AcceptHeaderClass acceptHeaderClass) {
            this.traceId = traceId;
            this.startedNanos = startedNanos;
            this.sampled = sampled;
            this.initialDispatcher = initialDispatcher;
            this.initialAsyncStarted = initialAsyncStarted;
            this.initialStatus = initialStatus;
            this.requestContentType = requestContentType;
            this.acceptsSse = acceptsSse;
            this.acceptsJson = acceptsJson;
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

    private static void safeInfo(String template, Object... arguments) {
        try {
            LOGGER.info(template, arguments);
        } catch (RuntimeException ignored) {
            // Servlet 诊断后端异常不能改变请求分派、异步生命周期或最终 HTTP 响应。
        }
    }

    private static void safeWarn(String template, Object... arguments) {
        try {
            LOGGER.warn(template, arguments);
        } catch (RuntimeException ignored) {
            // Servlet 诊断后端异常不能替换正在传播的业务异常。
        }
    }
}
