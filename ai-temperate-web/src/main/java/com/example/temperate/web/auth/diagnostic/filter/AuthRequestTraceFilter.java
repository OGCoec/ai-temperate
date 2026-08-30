package com.example.temperate.web.auth.diagnostic.filter;

import com.example.temperate.common.net.ip.IpAddressIdentity;
import com.example.temperate.web.auth.interceptor.UserSessionAuthenticationInterceptor;
import com.example.temperate.web.auth.phonecountry.component.TrustedClientIpResolver;
import com.example.temperate.web.risk.NetworkRiskInterceptor;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 为全部业务 API 建立不读取凭据内容的端到端认证诊断关联边界。
 *
 * <p>过滤器为每个 API 请求生成追踪标识，并记录前端请求与页面 UUID、客户端排队时间、固定认证阶段耗时、
 * Cookie Header 字节数和地址解析是否分歧。它不记录 Cookie、Token、请求体、完整 IP 或转发头原文，也不改变认证结果。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class AuthRequestTraceFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String ATTEMPT_HEADER = "X-Turnstile-Attempt-Id";
    public static final String CLIENT_REQUEST_HEADER = "X-AIT-Client-Request-Id";
    public static final String PAGE_INSTANCE_HEADER = "X-AIT-Page-Instance-Id";
    public static final String CLIENT_QUEUE_HEADER = "X-AIT-Client-Queue-Ms";
    public static final String WEBRTC_PROBE_RUN_HEADER = "X-AIT-WebRTC-Probe-Run-Id";
    public static final String TRACE_ATTRIBUTE =
            AuthRequestTraceFilter.class.getName() + ".traceId";
    public static final String INBOUND_CF_RAY_ATTRIBUTE =
            AuthRequestTraceFilter.class.getName() + ".inboundCfRay";
    public static final String ATTEMPT_ATTRIBUTE =
            AuthRequestTraceFilter.class.getName() + ".attemptId";
    public static final String COOKIE_BYTES_ATTRIBUTE =
            AuthRequestTraceFilter.class.getName() + ".cookieHeaderBytes";
    public static final String CLIENT_REQUEST_ATTRIBUTE =
            AuthRequestTraceFilter.class.getName() + ".clientRequestId";
    public static final String PAGE_INSTANCE_ATTRIBUTE =
            AuthRequestTraceFilter.class.getName() + ".pageInstanceId";
    public static final String CLIENT_QUEUE_ATTRIBUTE =
            AuthRequestTraceFilter.class.getName() + ".clientQueueMs";
    public static final String WEBRTC_PROBE_RUN_ATTRIBUTE =
            AuthRequestTraceFilter.class.getName() + ".webRtcProbeRunId";
    public static final String PAGE_INSTANCE_MDC_KEY = "pageInstanceId";
    public static final String WEBRTC_PROBE_RUN_MDC_KEY = "webRtcProbeRunId";

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthRequestTraceFilter.class);
    private static final String CF_RAY_HEADER = "CF-Ray";
    private static final String TRACE_MDC_KEY = "traceId";
    private static final String ATTEMPT_MDC_KEY = "turnstileAttemptId";
    private static final String CF_RAY_MDC_KEY = "inboundCfRay";
    private static final String CLIENT_REQUEST_MDC_KEY = "clientRequestId";
    private static final String REQUEST_PATH_MDC_KEY = "authRequestPath";
    private static final String CLIENT_PLATFORM_MDC_KEY = "authClientPlatform";

    private final TrustedClientIpResolver clientIpResolver;
    private final boolean enabled;
    private final boolean serverTimingEnabled;

    public AuthRequestTraceFilter(TrustedClientIpResolver clientIpResolver) {
        this(clientIpResolver, true, true);
    }

    @Autowired
    public AuthRequestTraceFilter(
            TrustedClientIpResolver clientIpResolver,
            @Value("${app.auth-request-diagnostics.enabled:true}") boolean enabled,
            @Value("${app.auth-request-diagnostics.server-timing-enabled:true}")
                    boolean serverTimingEnabled) {
        this.clientIpResolver = clientIpResolver;
        this.enabled = enabled;
        this.serverTimingEnabled = serverTimingEnabled;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        return !enabled
                || path == null
                || !(path.equals("/api") || path.startsWith("/api/"))
                || path.equals("/api/health");
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString();
        String inboundCfRay = safeBoundedIdentifier(request.getHeader(CF_RAY_HEADER), 128);
        String attemptId = safeAttemptId(request.getHeader(ATTEMPT_HEADER));
        String clientRequestId = safeUuid(request.getHeader(CLIENT_REQUEST_HEADER));
        String pageInstanceId = safeUuid(request.getHeader(PAGE_INSTANCE_HEADER));
        String webRtcProbeRunId = safeProbeRunId(
                request.getHeader(WEBRTC_PROBE_RUN_HEADER));
        long clientQueueMillis = safeClientQueueMillis(
                request.getHeader(CLIENT_QUEUE_HEADER));
        int cookieHeaderBytes = utf8Length(request.getHeader("Cookie"));
        ClientAddressDiagnostic addressDiagnostic = clientAddressDiagnostic(request);
        request.setAttribute(TRACE_ATTRIBUTE, traceId);
        request.setAttribute(INBOUND_CF_RAY_ATTRIBUTE, inboundCfRay);
        request.setAttribute(ATTEMPT_ATTRIBUTE, attemptId);
        request.setAttribute(COOKIE_BYTES_ATTRIBUTE, cookieHeaderBytes);
        request.setAttribute(CLIENT_REQUEST_ATTRIBUTE, clientRequestId);
        request.setAttribute(PAGE_INSTANCE_ATTRIBUTE, pageInstanceId);
        request.setAttribute(CLIENT_QUEUE_ATTRIBUTE, clientQueueMillis);
        request.setAttribute(WEBRTC_PROBE_RUN_ATTRIBUTE, webRtcProbeRunId);
        AuthRequestTiming.initialize(request, serverTimingEnabled);
        response.setHeader(TRACE_HEADER, traceId);
        if (!"absent".equals(webRtcProbeRunId)) {
            // 回显已经通过 UUID 校验的关联标识，便于浏览器把响应与本次探测尝试核对；非法客户端文本绝不回显。
            response.setHeader(WEBRTC_PROBE_RUN_HEADER, webRtcProbeRunId);
        }

        String previousTraceId = MDC.get(TRACE_MDC_KEY);
        String previousAttemptId = MDC.get(ATTEMPT_MDC_KEY);
        String previousCfRay = MDC.get(CF_RAY_MDC_KEY);
        String previousClientRequestId = MDC.get(CLIENT_REQUEST_MDC_KEY);
        String previousPageInstanceId = MDC.get(PAGE_INSTANCE_MDC_KEY);
        String previousWebRtcProbeRunId = MDC.get(WEBRTC_PROBE_RUN_MDC_KEY);
        String previousRequestPath = MDC.get(REQUEST_PATH_MDC_KEY);
        String previousClientPlatform = MDC.get(CLIENT_PLATFORM_MDC_KEY);
        MDC.put(TRACE_MDC_KEY, traceId);
        MDC.put(ATTEMPT_MDC_KEY, attemptId);
        MDC.put(CF_RAY_MDC_KEY, inboundCfRay);
        MDC.put(CLIENT_REQUEST_MDC_KEY, clientRequestId);
        MDC.put(PAGE_INSTANCE_MDC_KEY, pageInstanceId);
        MDC.put(WEBRTC_PROBE_RUN_MDC_KEY, webRtcProbeRunId);
        MDC.put(REQUEST_PATH_MDC_KEY, safePath(request.getRequestURI()));
        MDC.put(
                CLIENT_PLATFORM_MDC_KEY,
                safePlatform(request.getHeader("X-Client-Platform")));
        long startedNanos = System.nanoTime();
        CompletionLogger completionLogger = new CompletionLogger(
                request,
                response,
                traceId,
                attemptId,
                clientRequestId,
                pageInstanceId,
                webRtcProbeRunId,
                clientQueueMillis,
                inboundCfRay,
                cookieHeaderBytes,
                addressDiagnostic,
                startedNanos);
        boolean failed = false;
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            failed = true;
            completionLogger.log(true);
            throw exception;
        } finally {
            AuthRequestTiming.writeServerTiming(request, response);
            response.setHeader(TRACE_HEADER, traceId);
            if (!failed && !completionLogger.logged()) {
                if (request.isAsyncStarted()) {
                    try {
                        request.getAsyncContext().addListener(completionLogger);
                    } catch (IllegalStateException exception) {
                        // 异步上下文在监听器注册前完成时立即记录当前最终状态，避免整条请求缺少完成日志。
                        completionLogger.log(false);
                    }
                } else {
                    completionLogger.log(false);
                }
            }
            restoreMdc(TRACE_MDC_KEY, previousTraceId);
            restoreMdc(ATTEMPT_MDC_KEY, previousAttemptId);
            restoreMdc(CF_RAY_MDC_KEY, previousCfRay);
            restoreMdc(CLIENT_REQUEST_MDC_KEY, previousClientRequestId);
            restoreMdc(PAGE_INSTANCE_MDC_KEY, previousPageInstanceId);
            restoreMdc(WEBRTC_PROBE_RUN_MDC_KEY, previousWebRtcProbeRunId);
            restoreMdc(REQUEST_PATH_MDC_KEY, previousRequestPath);
            restoreMdc(CLIENT_PLATFORM_MDC_KEY, previousClientPlatform);
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
                            && addressesDiffer(remoteAddress, resolvedAddress.get()));
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

    private static String safeUuid(String value) {
        if (value == null || value.isBlank()) {
            return "absent";
        }
        try {
            String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
            return UUID.fromString(normalized).toString().equals(normalized)
                    ? normalized
                    : "invalid";
        } catch (IllegalArgumentException exception) {
            return "invalid";
        }
    }

    private static String safeProbeRunId(String value) {
        String normalized = safeUuid(value);
        // Probe ID 来自诊断客户端；非法输入按缺失处理，禁止把攻击者文本或 invalid 标签用于跨层关联。
        return "invalid".equals(normalized) ? "absent" : normalized;
    }

    private static long safeClientQueueMillis(String value) {
        if (value == null || !value.matches("^[0-9]{1,8}$")) {
            return -1L;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed <= 86_400_000L ? parsed : -1L;
        } catch (NumberFormatException exception) {
            return -1L;
        }
    }

    private static String safeMethod(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.matches("^[A-Z]{1,12}$") ? normalized : "INVALID";
    }

    private static String safePlatform(String value) {
        if (value == null) {
            return "unavailable";
        }
        return switch (value.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "H5" -> "H5";
            case "ANDROID" -> "ANDROID";
            default -> "unavailable";
        };
    }

    private static String safePath(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = value.replaceAll(
                "[^A-Za-z0-9._~!$&'()*+,;=:@%/{}-]", "_");
        // 邮件任务段在格式校验前也必须模板化，避免无效路径把攻击者输入写入认证诊断日志。
        normalized = normalized.replaceFirst(
                "^/api/admin/mail-inspection/jobs/[^/]+/events$",
                "/api/admin/mail-inspection/jobs/{jobId}/events");
        normalized = normalized.replaceFirst(
                "^/api/admin/mail-inspection/jobs/[^/]+/resume$",
                "/api/admin/mail-inspection/jobs/{jobId}/resume");
        normalized = normalized.replaceFirst(
                "^/api/admin/mail-inspection/jobs/[^/]+$",
                "/api/admin/mail-inspection/jobs/{jobId}");
        return normalized.length() <= 255 ? normalized : normalized.substring(0, 255);
    }

    private static String addressFamily(String value) {
        if (value == null || value.isBlank()) {
            return "ABSENT";
        }
        return IpAddressIdentity.tryParse(value)
                .map(identity -> identity.family().name())
                .orElse("INVALID");
    }

    private static boolean addressesDiffer(String left, String right) {
        Optional<IpAddressIdentity> leftIdentity = IpAddressIdentity.tryParse(left);
        Optional<IpAddressIdentity> rightIdentity = IpAddressIdentity.tryParse(right);
        return leftIdentity.isPresent()
                && rightIdentity.isPresent()
                && !leftIdentity.get().equals(rightIdentity.get());
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

    /**
     * 延迟到 Servlet 异步生命周期真正完成后记录一次最终状态，避免 Mono 初始分派的临时 200 误导排障。
     */
    private static final class CompletionLogger implements AsyncListener {

        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final String traceId;
        private final String attemptId;
        private final String clientRequestId;
        private final String pageInstanceId;
        private final String webRtcProbeRunId;
        private final long clientQueueMillis;
        private final String inboundCfRay;
        private final int cookieHeaderBytes;
        private final ClientAddressDiagnostic addressDiagnostic;
        private final long startedNanos;
        private final AtomicBoolean logged = new AtomicBoolean();
        private final AtomicBoolean asyncFailure = new AtomicBoolean();

        private CompletionLogger(
                HttpServletRequest request,
                HttpServletResponse response,
                String traceId,
                String attemptId,
                String clientRequestId,
                String pageInstanceId,
                String webRtcProbeRunId,
                long clientQueueMillis,
                String inboundCfRay,
                int cookieHeaderBytes,
                ClientAddressDiagnostic addressDiagnostic,
                long startedNanos) {
            this.request = request;
            this.response = response;
            this.traceId = traceId;
            this.attemptId = attemptId;
            this.clientRequestId = clientRequestId;
            this.pageInstanceId = pageInstanceId;
            this.webRtcProbeRunId = webRtcProbeRunId;
            this.clientQueueMillis = clientQueueMillis;
            this.inboundCfRay = inboundCfRay;
            this.cookieHeaderBytes = cookieHeaderBytes;
            this.addressDiagnostic = addressDiagnostic;
            this.startedNanos = startedNanos;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            log(false);
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            asyncFailure.set(true);
        }

        @Override
        public void onError(AsyncEvent event) {
            asyncFailure.set(true);
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }

        private boolean logged() {
            return logged.get();
        }

        private void log(boolean synchronousFailure) {
            if (!logged.compareAndSet(false, true)) {
                return;
            }
            boolean failed = synchronousFailure || asyncFailure.get();
            int status = failed && response.getStatus() < 400
                    ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                    : response.getStatus();
            AuthRequestTiming.writeServerTiming(request, response);
            LOGGER.info(
                    "auth_request_completed traceId={} clientRequestId={} pageInstanceId={} "
                            + "probeRunId={} "
                            + "clientQueueMs={} turnstileAttemptId={} inboundCfRay={} "
                            + "method={} path={} platform={} status={} errorCode={} clearCookies={} elapsedMs={} "
                            + "riskMs={} webrtcMs={} sessionMs={} preAuthBindingMs={} controllerMs={} "
                            + "cookieHeaderBytes={} accessCredentialPresent={} "
                            + "refreshCredentialPresent={} csrfHeaderPresent={} "
                            + "preAuthCredentialPresent={} preAuthAccessPresent={} "
                            + "bindingAttempted={} bindingResult={} "
                            + "remoteAddressFamily={} resolvedAddressFamily={} "
                            + "resolvedClientDiffers={}",
                    traceId,
                    clientRequestId,
                    pageInstanceId,
                    webRtcProbeRunId,
                    clientQueueMillis,
                    attemptId,
                    inboundCfRay,
                    safeMethod(request.getMethod()),
                    safeRoute(request),
                    safePlatform(request.getHeader("X-Client-Platform")),
                    status,
                    AuthRequestTiming.errorCode(request),
                    diagnosticValue(request, AuthRequestTiming.CLEAR_COOKIES_ATTRIBUTE),
                    elapsedMillis(startedNanos),
                    duration(request, AuthRequestTiming.Stage.RISK),
                    duration(request, AuthRequestTiming.Stage.WEBRTC),
                    duration(request, AuthRequestTiming.Stage.SESSION),
                    duration(request, AuthRequestTiming.Stage.PREAUTH_BINDING),
                    duration(request, AuthRequestTiming.Stage.CONTROLLER),
                    cookieHeaderBytes,
                    diagnosticValue(
                            request,
                            UserSessionAuthenticationInterceptor
                                    .ACCESS_CREDENTIAL_PRESENT_ATTRIBUTE),
                    diagnosticValue(
                            request,
                            UserSessionAuthenticationInterceptor
                                    .REFRESH_CREDENTIAL_PRESENT_ATTRIBUTE),
                    diagnosticValue(
                            request,
                            UserSessionAuthenticationInterceptor.CSRF_PRESENT_ATTRIBUTE),
                    diagnosticValue(
                            request,
                            NetworkRiskInterceptor.PREAUTH_CREDENTIAL_PRESENT_ATTRIBUTE),
                    diagnosticValue(
                            request,
                            UserSessionAuthenticationInterceptor
                                    .PREAUTH_ACCESS_PRESENT_ATTRIBUTE),
                    diagnosticValue(
                            request,
                            UserSessionAuthenticationInterceptor.BINDING_ATTEMPTED_ATTRIBUTE),
                    diagnosticValue(
                            request,
                            UserSessionAuthenticationInterceptor.BINDING_RESULT_ATTRIBUTE),
                    addressDiagnostic.remoteFamily(),
                    addressDiagnostic.resolvedFamily(),
                    addressDiagnostic.differs());
        }

        private static long duration(
                HttpServletRequest request,
                AuthRequestTiming.Stage stage) {
            return AuthRequestTiming.durationMillis(request, stage).orElse(-1L);
        }

        private static String diagnosticValue(
                HttpServletRequest request,
                String attributeName) {
            Object value = request.getAttribute(attributeName);
            return value == null ? "unavailable" : String.valueOf(value);
        }

        private static String safeRoute(HttpServletRequest request) {
            Object pattern = request.getAttribute(
                    HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            return safePath(pattern == null
                    ? request.getRequestURI()
                    : String.valueOf(pattern));
        }

    }
}
