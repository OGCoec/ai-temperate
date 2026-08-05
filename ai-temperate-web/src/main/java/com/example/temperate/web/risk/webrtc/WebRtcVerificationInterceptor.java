package com.example.temperate.web.risk.webrtc;

import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.observability.WebRtcMetrics;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.webrtc.domain.WebRtcVerificationDecision;
import com.example.temperate.service.risk.webrtc.domain.WebRtcVerificationOutcome;
import com.example.temperate.service.risk.webrtc.service.WebRtcVerificationService;
import com.example.temperate.web.auth.diagnostic.filter.AuthRequestTraceFilter;
import com.example.temperate.web.auth.session.transport.AuthClientPlatform;
import com.example.temperate.web.risk.NetworkRiskInterceptor;
import com.example.temperate.web.risk.RiskRequestContextResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 在网络风险已放行后解释 PreAuth WebRTC 异步状态，并在强制模式只阻止已失败或已超时的新请求。
 *
 * <p>该拦截器不创建 RTCPeerConnection，不请求 STUN，也不接受客户端匹配结论；Start/Report 路由由
 * 注册配置排除，以便客户端在同一 PreAuth 下完成闭环。同一 Servlet 请求的 ASYNC 二次分派只复用与
 * 原方法、路径和作用域完全一致的已放行结果，避免重复检查已轮换的 PreAuth。</p>
 */
@Component
public final class WebRtcVerificationInterceptor implements HandlerInterceptor {

    private static final String COMPLETED_VERIFICATION_ATTRIBUTE =
            WebRtcVerificationInterceptor.class.getName() + ".completedVerification";
    private static final Pattern SAFE_METHOD = Pattern.compile("^[A-Z]{1,16}$");
    private static final Pattern SAFE_PATH =
            Pattern.compile("^/[A-Za-z0-9._~!$&'()*+,;=:@%/-]{0,255}$");
    private static final Logger LOGGER =
            LoggerFactory.getLogger(WebRtcVerificationInterceptor.class);

    private final NetworkRiskProperties properties;
    private final WebRtcVerificationService verificationService;
    private final RiskRequestContextResolver contextResolver;
    private final ObjectMapper objectMapper;
    private final WebRtcMetrics metrics;
    private final WebRtcVerificationTransport transport;

    public WebRtcVerificationInterceptor(
            NetworkRiskProperties properties,
            WebRtcVerificationService verificationService,
            RiskRequestContextResolver contextResolver,
            ObjectMapper objectMapper,
            WebRtcMetrics metrics,
            WebRtcVerificationTransport transport) {
        this.properties = Objects.requireNonNull(properties);
        this.verificationService = Objects.requireNonNull(verificationService);
        this.contextResolver = Objects.requireNonNull(contextResolver);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.metrics = Objects.requireNonNull(metrics);
        this.transport = Objects.requireNonNull(transport);
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) throws Exception {
        RiskScope scope = scope(request);
        CompletedVerification completedVerification = completedVerification(request, scope);
        if (completedVerification != null) {
            logAsyncReuse(request, scope, completedVerification.outcome());
            return true;
        }
        if (properties.mode() == NetworkRiskMode.DISABLED
                || HttpMethod.OPTIONS.matches(request.getMethod())) {
            return allow(request, scope, "bypass");
        }
        String platform = platform(request);
        Object attribute = request.getAttribute(
                NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE);
        TrustedNetworkObservation observation = contextResolver.resolve(request)
                .orElse(null);
        if (!(attribute instanceof PreAuthAccess access) || observation == null) {
            metrics.interceptor(
                    scope,
                    "required",
                    platform,
                    properties.mode());
            if (properties.mode() == NetworkRiskMode.OBSERVE) {
                return allow(request, scope, "observe_required");
            }
            return rejectPreAuth(response);
        }

        WebRtcVerificationDecision decision = verificationService.inspect(
                access,
                observation.clientIp());
        if (Boolean.TRUE.equals(request.getAttribute(
                NetworkRiskInterceptor.WEBRTC_GENERATION_CHANGED_ATTRIBUTE))) {
            metrics.transition(
                    scope,
                    "generation_changed",
                    platform,
                    "network_changed",
                    properties.mode());
            request.removeAttribute(
                    NetworkRiskInterceptor.WEBRTC_GENERATION_CHANGED_ATTRIBUTE);
        }
        String metricDecision = interceptorDecision(decision.outcome());
        metrics.interceptor(scope, metricDecision, platform, properties.mode());
        transport.write(response, decision);
        if (decision.outcome() == WebRtcVerificationOutcome.VERIFIED
                || decision.outcome() == WebRtcVerificationOutcome.VERIFICATION_PENDING
                || decision.outcome() == WebRtcVerificationOutcome.VERIFICATION_REQUIRED
                || properties.mode() == NetworkRiskMode.OBSERVE) {
            return allow(request, scope, metricDecision);
        }
        return reject(
                response,
                httpStatus(decision.outcome()),
                scope,
                decision,
                observation.clientIp());
    }

    private boolean rejectPreAuth(HttpServletResponse response) throws Exception {
        response.setStatus(HttpStatus.PRECONDITION_REQUIRED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "PREAUTH_REQUIRED");
        body.put("message", "PreAuth is missing or no longer valid.");
        body.put("timestamp", Instant.now().toString());
        objectMapper.writeValue(response.getWriter(), body);
        return false;
    }

    private boolean reject(
            HttpServletResponse response,
            HttpStatus status,
            RiskScope scope,
            WebRtcVerificationDecision decision,
            String currentHttpIp) throws Exception {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code(decision.outcome()));
        body.put("message", message(decision.outcome()));
        body.put("webRtcStatus", decision.webRtcStatus());
        body.put("retryable", decision.outcome() == WebRtcVerificationOutcome.NETWORK_CHANGED
                || decision.outcome() == WebRtcVerificationOutcome.STALE_REPORT);
        if (decision.outcome() == WebRtcVerificationOutcome.VERIFICATION_REQUIRED) {
            body.put("startPath", startPath(scope));
            body.put("timeoutMillis", properties.webRtc().probeTimeout().toMillis());
        } else if (currentHttpIp != null) {
            body.put("httpIp", currentHttpIp);
            body.put("webRtcIps", decision.webRtcIps());
        }
        body.put("timestamp", Instant.now().toString());
        objectMapper.writeValue(response.getWriter(), body);
        return false;
    }

    private static boolean allow(
            HttpServletRequest request,
            RiskScope scope,
            String outcome) {
        // WebRTC 结果与网络风险结果分开标记，确保两个安全门都只复用各自已完成的校验。
        request.setAttribute(
                COMPLETED_VERIFICATION_ATTRIBUTE,
                new CompletedVerification(
                        request.getMethod(),
                        request.getRequestURI(),
                        scope,
                        outcome));
        return true;
    }

    private static CompletedVerification completedVerification(
            HttpServletRequest request,
            RiskScope scope) {
        if (request.getDispatcherType() != DispatcherType.ASYNC) {
            return null;
        }
        Object attribute = request.getAttribute(COMPLETED_VERIFICATION_ATTRIBUTE);
        if (!(attribute instanceof CompletedVerification completed)) {
            return null;
        }
        return completed.matches(request, scope) ? completed : null;
    }

    private static void logAsyncReuse(
            HttpServletRequest request,
            RiskScope scope,
            String outcome) {
        Object traceAttribute = request.getAttribute(AuthRequestTraceFilter.TRACE_ATTRIBUTE);
        Object invocationAttribute = request.getAttribute(
                NetworkRiskInterceptor.DIAGNOSTIC_INVOCATION_ATTRIBUTE);
        String traceId = traceAttribute instanceof String trace ? trace : "absent";
        int invocationNo = invocationAttribute instanceof Number number
                ? Math.max(0, number.intValue())
                : 0;
        LOGGER.debug(
                "event=webrtc_async_result_reused traceId={} invocationNo={} "
                        + "dispatcherType={} scope={} method={} path={} outcome={}",
                traceId,
                invocationNo,
                request.getDispatcherType(),
                scope,
                safeMethod(request.getMethod()),
                safePath(request.getRequestURI()),
                outcome);
    }

    private static String safeMethod(String value) {
        return value != null && SAFE_METHOD.matcher(value).matches()
                ? value
                : "unavailable";
    }

    private static String safePath(String value) {
        return value != null && SAFE_PATH.matcher(value).matches()
                ? value
                : "unavailable";
    }

    private static HttpStatus httpStatus(WebRtcVerificationOutcome outcome) {
        return switch (outcome) {
            case IP_MISMATCH -> HttpStatus.FORBIDDEN;
            case NETWORK_CHANGED, STALE_REPORT -> HttpStatus.CONFLICT;
            case VERIFICATION_REQUIRED, VERIFICATION_FAILED,
                    VERIFICATION_TIMEOUT, VERIFICATION_PENDING ->
                    HttpStatus.PRECONDITION_REQUIRED;
            case STATE_INVALID -> HttpStatus.SERVICE_UNAVAILABLE;
            case VERIFIED -> HttpStatus.OK;
        };
    }

    private static String code(WebRtcVerificationOutcome outcome) {
        return switch (outcome) {
            case VERIFIED -> "WEBRTC_VERIFIED";
            case VERIFICATION_PENDING -> "WEBRTC_VERIFICATION_PENDING";
            case VERIFICATION_REQUIRED -> "WEBRTC_VERIFICATION_REQUIRED";
            case VERIFICATION_FAILED -> "WEBRTC_VERIFICATION_FAILED";
            case VERIFICATION_TIMEOUT -> "WEBRTC_VERIFICATION_TIMEOUT";
            case IP_MISMATCH -> "WEBRTC_IP_MISMATCH";
            case NETWORK_CHANGED -> "WEBRTC_NETWORK_CHANGED";
            case STALE_REPORT -> "WEBRTC_REPORT_STALE";
            case STATE_INVALID -> "WEBRTC_STATE_UNAVAILABLE";
        };
    }

    private static String message(WebRtcVerificationOutcome outcome) {
        return switch (outcome) {
            case VERIFIED -> "WebRTC 网络一致性校验已通过。";
            case VERIFICATION_PENDING -> "WebRTC 网络一致性校验正在后台进行。";
            case VERIFICATION_REQUIRED -> "请先完成 WebRTC 网络一致性校验。";
            case VERIFICATION_FAILED ->
                    "未获取到可用于校验的 WebRTC 公网 IP，当前会话已停止访问。";
            case VERIFICATION_TIMEOUT -> "WebRTC 网络一致性校验已超时，当前会话已停止访问。";
            case IP_MISMATCH ->
                    "检测到 WebRTC IP 与当前 HTTP IP 不一致，当前会话已停止访问。";
            case NETWORK_CHANGED -> "检测期间网络环境发生变化，请读取最新探测状态。";
            case STALE_REPORT -> "该 WebRTC Report 已过期，请读取最新探测状态。";
            case STATE_INVALID -> "WebRTC 校验状态暂时不可用。";
        };
    }

    private static String interceptorDecision(WebRtcVerificationOutcome outcome) {
        return switch (outcome) {
            case VERIFIED -> "allowed";
            case VERIFICATION_PENDING -> "pending_allowed";
            case VERIFICATION_REQUIRED -> "required_allowed";
            case NETWORK_CHANGED, STALE_REPORT -> "required";
            case VERIFICATION_FAILED, VERIFICATION_TIMEOUT -> "failed";
            case IP_MISMATCH -> "blocked";
            case STATE_INVALID -> "invalid";
        };
    }

    private static String platform(HttpServletRequest request) {
        return AuthClientPlatform.fromHeader(
                        request.getHeader("X-Client-Platform"))
                == AuthClientPlatform.ANDROID
                ? "android"
                : "h5";
    }

    private static RiskScope scope(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && path.startsWith("/api/admin")
                ? RiskScope.ADMIN
                : RiskScope.USER;
    }

    private static String startPath(RiskScope scope) {
        return scope == RiskScope.ADMIN
                ? "/api/admin/_edge/webrtc/start"
                : "/api/_edge/webrtc/start";
    }

    /**
     * 绑定一次已放行 WebRTC 校验的原始请求边界，避免异步转发到其他路径时沿用旧结果。
     */
    private record CompletedVerification(
            String method,
            String requestUri,
            RiskScope scope,
            String outcome) {

        private boolean matches(HttpServletRequest request, RiskScope currentScope) {
            return Objects.equals(method, request.getMethod())
                    && Objects.equals(requestUri, request.getRequestURI())
                    && scope == currentScope;
        }
    }
}
