package com.example.temperate.web.risk;

import com.example.temperate.service.risk.challenge.RiskChallengeIssue;
import com.example.temperate.service.risk.challenge.RiskChallengeService;
import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.decision.NetworkRiskAssessmentService;
import com.example.temperate.service.risk.decision.RiskAssessment;
import com.example.temperate.service.risk.domain.RiskDecision;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.observability.NetworkRiskDiagnosticContext;
import com.example.temperate.service.risk.observability.NetworkRiskMetrics;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthRequiredException;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.web.auth.diagnostic.filter.AuthRequestTraceFilter;
import com.example.temperate.web.auth.session.transport.AuthClientPlatform;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 在所有 MVC 业务拦截器之前执行 PreAuth、同 IP 快速路径和 IP 变化实时风险决策。
 *
 * <p>该拦截器不替代 CSRF、会话、权限或设备封禁；OBSERVE 仍计算并记录决策但不阻断，ENFORCE 才返回
 * 428 Challenge 或 403 动态 Block。同一 Servlet 请求进入 ASYNC 二次分派时只复用与原方法、路径和作用域
 * 完全一致的已放行结果，新的 HTTP 请求始终重新校验。</p>
 */
@Component
public final class NetworkRiskInterceptor implements HandlerInterceptor {

    public static final String PREAUTH_ACCESS_ATTRIBUTE =
            NetworkRiskInterceptor.class.getName() + ".preAuthAccess";
    public static final String DIAGNOSTIC_INVOCATION_ATTRIBUTE =
            NetworkRiskInterceptor.class.getName() + ".diagnosticInvocation";
    public static final String WEBRTC_GENERATION_CHANGED_ATTRIBUTE =
            NetworkRiskInterceptor.class.getName() + ".webRtcGenerationChanged";
    private static final String COMPLETED_EVALUATION_ATTRIBUTE =
            NetworkRiskInterceptor.class.getName() + ".completedEvaluation";
    private static final String DEVICE_HEADER = "X-Device-Installation-Id";
    private static final String DIAGNOSTIC_PHASE = "network_risk_prehandle";
    private static final Pattern SAFE_METHOD = Pattern.compile("^[A-Z]{1,16}$");
    private static final Pattern SAFE_PATH =
            Pattern.compile("^/[A-Za-z0-9._~!$&'()*+,;=:@%/-]{0,255}$");
    private static final Logger LOGGER =
            LoggerFactory.getLogger(NetworkRiskInterceptor.class);

    private final NetworkRiskProperties properties;
    private final PreAuthService preAuthService;
    private final NetworkRiskAssessmentService assessmentService;
    private final RiskChallengeService challengeService;
    private final RiskRequestContextResolver contextResolver;
    private final PreAuthTransport transport;
    private final ObjectMapper objectMapper;
    private final NetworkRiskMetrics metrics;

    public NetworkRiskInterceptor(
            NetworkRiskProperties properties,
            PreAuthService preAuthService,
            NetworkRiskAssessmentService assessmentService,
            RiskChallengeService challengeService,
            RiskRequestContextResolver contextResolver,
            PreAuthTransport transport,
            ObjectMapper objectMapper,
            NetworkRiskMetrics metrics) {
        this.properties = Objects.requireNonNull(properties);
        this.preAuthService = Objects.requireNonNull(preAuthService);
        this.assessmentService = Objects.requireNonNull(assessmentService);
        this.challengeService = Objects.requireNonNull(challengeService);
        this.contextResolver = Objects.requireNonNull(contextResolver);
        this.transport = Objects.requireNonNull(transport);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) throws Exception {
        int invocationNo = nextInvocationNo(request);
        String dispatcherType = request.getDispatcherType().name();
        String traceId = traceId(request);
        try (NetworkRiskDiagnosticContext.Scope ignored =
                NetworkRiskDiagnosticContext.open(
                        traceId,
                        invocationNo,
                        dispatcherType,
                        DIAGNOSTIC_PHASE)) {
            return evaluate(request, response);
        }
    }

    private boolean evaluate(
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        RiskScope scope = scope(request);
        Object existingAccess = request.getAttribute(PREAUTH_ACCESS_ATTRIBUTE);
        logEntry(request, response, scope, existingAccess instanceof PreAuthAccess);
        CompletedEvaluation completedEvaluation = completedEvaluation(request, scope);
        if (completedEvaluation != null) {
            logAsyncReuse(request, scope, completedEvaluation.outcome());
            return true;
        }
        if (properties.mode() == NetworkRiskMode.DISABLED
                || HttpMethod.OPTIONS.matches(request.getMethod())) {
            return allow(request, scope, "bypass");
        }
        PreAuthAccess access = existingAccess instanceof PreAuthAccess verified
                ? verified
                : preAuthService.resolve(
                                scope,
                                transport.read(request, scope),
                                request.getHeader(DEVICE_HEADER))
                        .orElse(null);
        if (access == null) {
            metrics.rejection(scope, "preauth_required");
            if (properties.mode() == NetworkRiskMode.OBSERVE) {
                return allow(request, scope, "observe_preauth_missing");
            }
            return reject(
                    request,
                    response,
                    HttpStatus.PRECONDITION_REQUIRED,
                    "PREAUTH_REQUIRED",
                    RejectionReason.PREAUTH_MISSING,
                    Map.of());
        }
        // 下游会话续期必须复用同一次已校验的 PreAuth，禁止重新解析不同作用域或不同设备的客户端值。
        request.setAttribute(PREAUTH_ACCESS_ATTRIBUTE, access);
        TrustedNetworkObservation observation = contextResolver.resolve(request)
                .orElse(null);
        if (observation == null) {
            metrics.rejection(scope, "edge_context_unavailable");
            if (properties.mode() == NetworkRiskMode.OBSERVE) {
                return allow(request, scope, "observe_edge_context_unavailable");
            }
            return reject(
                    request,
                    response,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "RISK_CONTEXT_UNAVAILABLE",
                    RejectionReason.EDGE_CONTEXT_UNAVAILABLE,
                    Map.of());
        }

        final RiskAssessment assessment;
        try {
            assessment = assessmentService
                    .assess(access, observation)
                    .block(properties.lookupTimeout());
        } catch (PreAuthRequiredException exception) {
            metrics.rejection(scope, "preauth_concurrent_expiry");
            if (properties.mode() == NetworkRiskMode.OBSERVE) {
                return allow(request, scope, "observe_preauth_concurrent_expiry");
            }
            return reject(
                    request,
                    response,
                    HttpStatus.PRECONDITION_REQUIRED,
                    "PREAUTH_REQUIRED",
                    RejectionReason.PREAUTH_CONCURRENT_EXPIRY,
                    Map.of());
        } catch (RuntimeException exception) {
            // 供应商或 Redis 异常不记录 IP、Token 或设备值；观察模式放行，强制模式失败关闭。
            NetworkRiskDiagnosticContext.Snapshot diagnostic =
                    NetworkRiskDiagnosticContext.current();
            LOGGER.warn(
                    "event=network_risk_assessment_unavailable traceId={} invocationNo={} "
                            + "dispatcherType={} mode={} scope={} exceptionClass={}",
                    diagnostic.traceId(),
                    diagnostic.invocationNo(),
                    diagnostic.dispatcherType(),
                    properties.mode(),
                    scope,
                    exception.getClass().getSimpleName());
            metrics.rejection(scope, "assessment_unavailable");
            if (properties.mode() == NetworkRiskMode.OBSERVE) {
                return allow(request, scope, "observe_assessment_unavailable");
            }
            return reject(
                    request,
                    response,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "RISK_ASSESSMENT_UNAVAILABLE",
                    RejectionReason.ASSESSMENT_UNAVAILABLE,
                    Map.of());
        }
        if (assessment != null
                && access.state() != null
                && access.state().currentIpDigest() != null
                && !access.state().currentIpDigest().equals(
                        assessment.currentIpDigest())) {
            // IP 变化已在 Redis 原子提升 WebRTC generation；下游必须改用新快照，禁止登录轮换继承旧 VERIFIED。
            PreAuthAccess refreshedAccess = preAuthService.resolve(
                            scope,
                            transport.read(request, scope),
                            request.getHeader(DEVICE_HEADER))
                    .orElse(null);
            if (refreshedAccess == null) {
                metrics.rejection(scope, "preauth_concurrent_expiry");
                if (properties.mode() == NetworkRiskMode.OBSERVE) {
                    return allow(request, scope, "observe_preauth_concurrent_expiry");
                }
                return reject(
                        request,
                        response,
                        HttpStatus.PRECONDITION_REQUIRED,
                        "PREAUTH_REQUIRED",
                        RejectionReason.PREAUTH_CONCURRENT_EXPIRY,
                        Map.of());
            }
            access = refreshedAccess;
            request.setAttribute(PREAUTH_ACCESS_ATTRIBUTE, refreshedAccess);
            // 只传递低敏布尔信号，让后置 WebRTC 门禁准确记录本请求发生过 generation 提升。
            request.setAttribute(WEBRTC_GENERATION_CHANGED_ATTRIBUTE, Boolean.TRUE);
        }
        if (assessment == null || properties.mode() == NetworkRiskMode.OBSERVE) {
            logAllowed(scope, assessment, "observe_or_empty");
            return allow(request, scope, "observe_or_empty");
        }
        if (assessment.decision() == RiskDecision.ALLOW) {
            logAllowed(scope, assessment, "allow");
            return allow(request, scope, "allow");
        }
        if (assessment.decision() == RiskDecision.BLOCK) {
            metrics.rejection(scope, "dynamic_block");
            return reject(
                    request,
                    response,
                    HttpStatus.FORBIDDEN,
                    "RISK_BLOCKED",
                    RejectionReason.DYNAMIC_BLOCK,
                    Map.of());
        }
        if (AuthClientPlatform.fromHeader(
                        request.getHeader("X-Client-Platform"))
                == AuthClientPlatform.ANDROID) {
            // 原生客户端无法完成浏览器 WAF 页面，不签发无用引用，要求用户切换可信网络后重试。
            metrics.rejection(scope, "challenge_unavailable_android");
            return reject(
                    request,
                    response,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "RISK_CHALLENGE_UNAVAILABLE",
                    RejectionReason.CHALLENGE_REQUIRED,
                    Map.of());
        }
        RiskChallengeIssue issue = challengeService.issue(access, assessment);
        metrics.rejection(scope, "challenge_required");
        String challengePath = scope == RiskScope.ADMIN
                ? "/api/admin/_edge/risk-challenge"
                : "/api/_edge/risk-challenge";
        return reject(
                request,
                response,
                HttpStatus.PRECONDITION_REQUIRED,
                "RISK_CHALLENGE_REQUIRED",
                RejectionReason.CHALLENGE_REQUIRED,
                Map.of(
                        "challengeRef", issue.reference(),
                        "challengePath", challengePath,
                        "expiresAt", issue.expiresAt().toString()));
    }

    private boolean reject(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String code,
            RejectionReason reason,
            Map<String, String> details) throws Exception {
        NetworkRiskDiagnosticContext.Snapshot diagnostic =
                NetworkRiskDiagnosticContext.current();
        LOGGER.warn(
                "event=network_risk_rejected traceId={} invocationNo={} dispatcherType={} "
                        + "phase={} reason={} status={} method={} path={} "
                        + "responseStatus={} responseCommitted={}",
                diagnostic.traceId(),
                diagnostic.invocationNo(),
                diagnostic.dispatcherType(),
                diagnostic.phase(),
                reason.value,
                status.value(),
                safeMethod(request.getMethod()),
                safePath(request.getRequestURI()),
                response.getStatus(),
                response.isCommitted());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("code", code);
        body.put("message", "The network risk request was rejected.");
        body.put("timestamp", Instant.now().toString());
        body.putAll(details);
        objectMapper.writeValue(response.getWriter(), body);
        return false;
    }

    private void logEntry(
            HttpServletRequest request,
            HttpServletResponse response,
            RiskScope scope,
            boolean preAuthAttributePresent) {
        NetworkRiskDiagnosticContext.Snapshot diagnostic =
                NetworkRiskDiagnosticContext.current();
        LOGGER.debug(
                "event=network_risk_prehandle_enter traceId={} invocationNo={} dispatcherType={} "
                        + "phase={} asyncStarted={} hasConcurrentResult={} scope={} mode={} "
                        + "method={} path={} preAuthAttributePresent={} responseStatus={} "
                        + "responseCommitted={}",
                diagnostic.traceId(),
                diagnostic.invocationNo(),
                diagnostic.dispatcherType(),
                diagnostic.phase(),
                request.isAsyncStarted(),
                WebAsyncUtils.getAsyncManager(request).hasConcurrentResult(),
                scope,
                properties.mode(),
                safeMethod(request.getMethod()),
                safePath(request.getRequestURI()),
                preAuthAttributePresent,
                response.getStatus(),
                response.isCommitted());
    }

    private static void logAllowed(
            RiskScope scope,
            RiskAssessment assessment,
            String outcome) {
        NetworkRiskDiagnosticContext.Snapshot diagnostic =
                NetworkRiskDiagnosticContext.current();
        LOGGER.debug(
                "event=network_risk_allowed traceId={} invocationNo={} dispatcherType={} "
                        + "phase={} scope={} outcome={} decision={}",
                diagnostic.traceId(),
                diagnostic.invocationNo(),
                diagnostic.dispatcherType(),
                diagnostic.phase(),
                scope,
                outcome,
                assessment == null ? "unavailable" : assessment.decision());
    }

    private static boolean allow(
            HttpServletRequest request,
            RiskScope scope,
            String outcome) {
        // 该标记只属于当前 Servlet 请求，用于让同一逻辑请求的 ASYNC 分派复用已完成决策。
        request.setAttribute(
                COMPLETED_EVALUATION_ATTRIBUTE,
                new CompletedEvaluation(
                        request.getMethod(),
                        request.getRequestURI(),
                        scope,
                        outcome));
        return true;
    }

    private static CompletedEvaluation completedEvaluation(
            HttpServletRequest request,
            RiskScope scope) {
        if (request.getDispatcherType() != DispatcherType.ASYNC) {
            return null;
        }
        Object attribute = request.getAttribute(COMPLETED_EVALUATION_ATTRIBUTE);
        if (!(attribute instanceof CompletedEvaluation completed)) {
            return null;
        }
        return completed.matches(request, scope) ? completed : null;
    }

    private static void logAsyncReuse(
            HttpServletRequest request,
            RiskScope scope,
            String outcome) {
        NetworkRiskDiagnosticContext.Snapshot diagnostic =
                NetworkRiskDiagnosticContext.current();
        LOGGER.debug(
                "event=network_risk_async_result_reused traceId={} invocationNo={} "
                        + "dispatcherType={} phase={} scope={} method={} path={} outcome={}",
                diagnostic.traceId(),
                diagnostic.invocationNo(),
                diagnostic.dispatcherType(),
                diagnostic.phase(),
                scope,
                safeMethod(request.getMethod()),
                safePath(request.getRequestURI()),
                outcome);
    }

    private static int nextInvocationNo(HttpServletRequest request) {
        Object current = request.getAttribute(DIAGNOSTIC_INVOCATION_ATTRIBUTE);
        int previous = current instanceof Number number
                ? Math.max(0, number.intValue())
                : 0;
        int next = previous == Integer.MAX_VALUE ? Integer.MAX_VALUE : previous + 1;
        request.setAttribute(DIAGNOSTIC_INVOCATION_ATTRIBUTE, next);
        return next;
    }

    private static String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(AuthRequestTraceFilter.TRACE_ATTRIBUTE);
        return value instanceof String trace ? trace : "absent";
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

    private static RiskScope scope(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && path.startsWith("/api/admin")
                ? RiskScope.ADMIN
                : RiskScope.USER;
    }

    private enum RejectionReason {
        PREAUTH_MISSING("preauth_missing"),
        PREAUTH_CONCURRENT_EXPIRY("preauth_concurrent_expiry"),
        EDGE_CONTEXT_UNAVAILABLE("edge_context_unavailable"),
        ASSESSMENT_UNAVAILABLE("assessment_unavailable"),
        DYNAMIC_BLOCK("dynamic_block"),
        CHALLENGE_REQUIRED("challenge_required");

        private final String value;

        RejectionReason(String value) {
            this.value = value;
        }
    }

    /**
     * 绑定一次已放行风险决策的原始请求边界，防止异步转发到其他路径时错误复用安全结果。
     */
    private record CompletedEvaluation(
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
