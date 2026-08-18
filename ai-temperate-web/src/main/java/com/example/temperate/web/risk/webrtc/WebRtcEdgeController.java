package com.example.temperate.web.risk.webrtc;

import com.example.temperate.service.admin.config.properties.AdminProperties;
import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.observability.WebRtcMetrics;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcFailureReason;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcPhase;
import com.example.temperate.service.risk.webrtc.domain.WebRtcVerificationDecision;
import com.example.temperate.service.risk.webrtc.domain.WebRtcVerificationOutcome;
import com.example.temperate.service.risk.webrtc.service.WebRtcVerificationService;
import com.example.temperate.service.risk.webrtc.validation.WebRtcInvalidReportException;
import com.example.temperate.web.auth.config.properties.AuthSecurityProperties;
import com.example.temperate.web.auth.session.transport.AuthClientPlatform;
import com.example.temperate.web.risk.NetworkRiskInterceptor;
import com.example.temperate.web.risk.RiskRequestContextResolver;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 为普通用户和管理员提供客户端 WebRTC 探测配置与结果报告接口。
 *
 * <p>Controller 只编排结构化 JSON、Origin/平台约束和 HTTP 状态；STUN 由浏览器或 Android 系统
 * WebView 执行，服务端只使用可信请求 IP 计算结果，不接受客户端提交的 HTTP IP 或匹配状态。</p>
 */
@RestController
@Tag(
        name = "安全-WebRTC 网络一致性",
        description = "为普通 H5、管理员 H5 与 Android 下发固定 STUN 探测参数并校验上报的公网候选集合；接口依赖已通过网络风险的 PreAuth，不负责登录认证，也不会由服务端发起 STUN。")
public final class WebRtcEdgeController {

    private static final String DEVICE_HEADER = "X-Device-Installation-Id";
    private static final String PLATFORM_HEADER = "X-Client-Platform";

    private final NetworkRiskProperties properties;
    private final AuthSecurityProperties authSecurityProperties;
    private final AdminProperties adminProperties;
    private final WebRtcVerificationService verificationService;
    private final RiskRequestContextResolver contextResolver;
    private final WebRtcMetrics metrics;

    public WebRtcEdgeController(
            NetworkRiskProperties properties,
            AuthSecurityProperties authSecurityProperties,
            AdminProperties adminProperties,
            WebRtcVerificationService verificationService,
            RiskRequestContextResolver contextResolver,
            WebRtcMetrics metrics) {
        this.properties = Objects.requireNonNull(properties);
        this.authSecurityProperties = Objects.requireNonNull(authSecurityProperties);
        this.adminProperties = Objects.requireNonNull(adminProperties);
        this.verificationService = Objects.requireNonNull(verificationService);
        this.contextResolver = Objects.requireNonNull(contextResolver);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @GetMapping("/api/_edge/webrtc/start")
    @Operation(summary = "获取普通用户 WebRTC 客户端探测配置")
    public ResponseEntity<WebRtcStartResponse> startUser(
            @NotBlank @RequestHeader(DEVICE_HEADER) String device,
            @NotBlank @RequestHeader(PLATFORM_HEADER) String platform,
            HttpServletRequest request) {
        return start(RiskScope.USER, platform, request);
    }

    @GetMapping("/api/admin/_edge/webrtc/start")
    @Operation(summary = "获取管理员 WebRTC 客户端探测配置")
    public ResponseEntity<WebRtcStartResponse> startAdmin(
            @NotBlank @RequestHeader(DEVICE_HEADER) String device,
            @NotBlank @RequestHeader(PLATFORM_HEADER) String platform,
            HttpServletRequest request) {
        return start(RiskScope.ADMIN, platform, request);
    }

    @PostMapping("/api/_edge/webrtc/report")
    @Operation(summary = "报告普通用户 WebRTC 公网候选集合")
    public ResponseEntity<WebRtcVerificationResponse> reportUser(
            @NotBlank @RequestHeader(DEVICE_HEADER) String device,
            @NotBlank @RequestHeader(PLATFORM_HEADER) String platform,
            @Valid @RequestBody WebRtcReportRequest body,
            HttpServletRequest request) {
        return report(RiskScope.USER, platform, body, request);
    }

    @PostMapping("/api/admin/_edge/webrtc/report")
    @Operation(summary = "报告管理员 WebRTC 公网候选集合")
    public ResponseEntity<WebRtcVerificationResponse> reportAdmin(
            @NotBlank @RequestHeader(DEVICE_HEADER) String device,
            @NotBlank @RequestHeader(PLATFORM_HEADER) String platform,
            @Valid @RequestBody WebRtcReportRequest body,
            HttpServletRequest request) {
        return report(RiskScope.ADMIN, platform, body, request);
    }

    private ResponseEntity<WebRtcStartResponse> start(
            RiskScope scope,
            String platformHeader,
            HttpServletRequest request) {
        AuthClientPlatform platform = requireTransport(scope, platformHeader, request);
        if (properties.mode() == NetworkRiskMode.DISABLED) {
            return noStore(ResponseEntity.ok(new WebRtcStartResponse(
                    properties.mode().name(),
                    "DISABLED",
                    false,
                    null,
                    null,
                    null,
                    0L,
                    properties.webRtc().probeTimeout().toMillis(),
                    properties.webRtc().reportGrace().toMillis(),
                    stunUrls(),
                    reportPath(scope),
                    null,
                    null,
                    null,
                    null,
                    null)));
        }
        PreAuthAccess access = requireAccess(request);
        TrustedNetworkObservation observation = requireObservation(request);
        WebRtcVerificationDecision decision = verificationService.begin(
                access,
                observation.clientIp());
        recordStartTransition(scope, platform, access, decision);
        boolean probeRequired = decision.outcome()
                == WebRtcVerificationOutcome.VERIFICATION_PENDING;
        String code = decision.outcome() == WebRtcVerificationOutcome.VERIFIED
                ? null
                : code(decision.outcome());
        String message = code == null ? null : message(decision.outcome());
        String httpIp = decision.webRtcStatus() == null
                || Boolean.TRUE.equals(decision.webRtcStatus())
                ? null
                : observation.clientIp();
        List<String> ips = decision.webRtcStatus() == null
                || Boolean.TRUE.equals(decision.webRtcStatus())
                ? null
                : decision.webRtcIps();
        return noStore(ResponseEntity.ok(new WebRtcStartResponse(
                properties.mode().name(),
                decision.verificationState(),
                probeRequired,
                decision.webRtcStatus(),
                decision.probeGeneration() > 0
                        ? Long.toString(decision.probeGeneration())
                        : null,
                decision.pendingUntil(),
                decision.pendingRemainingMillis(),
                properties.webRtc().probeTimeout().toMillis(),
                properties.webRtc().reportGrace().toMillis(),
                stunUrls(),
                reportPath(scope),
                code,
                message,
                httpIp,
                ips,
                Boolean.FALSE.equals(decision.webRtcStatus())
                        ? Boolean.FALSE
                        : null)));
    }

    private ResponseEntity<WebRtcVerificationResponse> report(
            RiskScope scope,
            String platformHeader,
            WebRtcReportRequest body,
            HttpServletRequest request) {
        AuthClientPlatform platform = requireTransport(scope, platformHeader, request);
        if (properties.mode() == NetworkRiskMode.DISABLED) {
            return noStore(ResponseEntity.ok(new WebRtcVerificationResponse(
                    "WEBRTC_DISABLED",
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    Instant.now())));
        }
        PreAuthAccess access = requireAccess(request);
        TrustedNetworkObservation observation = requireObservation(request);
        final WebRtcVerificationDecision decision;
        try {
            decision = verificationService.report(
                    access,
                    observation.clientIp(),
                    body.probeGeneration(),
                    body.webRtcIps());
        } catch (WebRtcInvalidReportException exception) {
            metrics.verification(
                    scope,
                    "invalid",
                    metricPlatform(platform),
                    properties.mode());
            return noStore(ResponseEntity.badRequest().body(
                    new WebRtcVerificationResponse(
                            "WEBRTC_REPORT_INVALID",
                            "WebRTC IP 报告格式无效。",
                            null,
                            null,
                            null,
                            null,
                            false,
                            Instant.now())));
        }
        metrics.verification(
                scope,
                verificationOutcome(decision.outcome()),
                metricPlatform(platform),
                properties.mode());
        recordReportTransition(scope, platform, access, decision);
        HttpStatus status = reportStatus(decision.outcome(), properties.mode());
        String httpIp = switch (decision.outcome()) {
            case VERIFICATION_FAILED, VERIFICATION_TIMEOUT,
                    IP_FAMILY_INCOMPLETE, IP_MISMATCH ->
                    observation.clientIp();
            default -> null;
        };
        List<String> ips = switch (decision.outcome()) {
            case VERIFICATION_FAILED, VERIFICATION_TIMEOUT,
                    IP_FAMILY_INCOMPLETE, IP_MISMATCH ->
                    decision.webRtcIps();
            default -> null;
        };
        return noStore(ResponseEntity.status(status).body(
                new WebRtcVerificationResponse(
                        code(decision.outcome()),
                        decision.outcome() == WebRtcVerificationOutcome.VERIFIED
                                ? null
                                : message(decision.outcome()),
                        decision.webRtcStatus(),
                        decision.verificationState(),
                        httpIp,
                        ips,
                        decision.outcome() == WebRtcVerificationOutcome.NETWORK_CHANGED
                                || decision.outcome() == WebRtcVerificationOutcome.STALE_REPORT,
                        Instant.now())));
    }

    private AuthClientPlatform requireTransport(
            RiskScope scope,
            String platformHeader,
            HttpServletRequest request) {
        if (!"H5".equalsIgnoreCase(platformHeader)
                && !"ANDROID".equalsIgnoreCase(platformHeader)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "WebRTC client platform is invalid.");
        }
        AuthClientPlatform platform = AuthClientPlatform.fromHeader(platformHeader);
        String origin = request.getHeader("Origin");
        if (platform == AuthClientPlatform.ANDROID) {
            if (origin != null && !origin.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Android WebRTC transport cannot carry Origin.");
            }
            return platform;
        }
        List<String> allowedOrigins = scope == RiskScope.ADMIN
                ? adminProperties.allowedOrigins()
                : authSecurityProperties.cors().allowedOrigins();
        if (origin == null || !allowedOrigins.contains(origin)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "WebRTC Origin is not allowed.");
        }
        return platform;
    }

    private static PreAuthAccess requireAccess(HttpServletRequest request) {
        Object value = request.getAttribute(
                NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE);
        if (value instanceof PreAuthAccess access) {
            return access;
        }
        throw new ResponseStatusException(
                HttpStatus.PRECONDITION_REQUIRED,
                "PreAuth is required.");
    }

    private TrustedNetworkObservation requireObservation(HttpServletRequest request) {
        return contextResolver.resolve(request).orElseThrow(() ->
                new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Trusted network context is unavailable."));
    }

    private List<String> stunUrls() {
        return properties.webRtc().stunUrls().stream()
                .map(Object::toString)
                .toList();
    }

    private static HttpStatus reportStatus(
            WebRtcVerificationOutcome outcome,
            NetworkRiskMode mode) {
        if (mode == NetworkRiskMode.OBSERVE
                && outcome != WebRtcVerificationOutcome.STATE_INVALID) {
            return HttpStatus.OK;
        }
        return switch (outcome) {
            case VERIFIED -> HttpStatus.OK;
            case IP_MISMATCH -> HttpStatus.FORBIDDEN;
            case VERIFICATION_FAILED, VERIFICATION_TIMEOUT,
                    IP_FAMILY_INCOMPLETE,
                    VERIFICATION_REQUIRED, VERIFICATION_PENDING ->
                    HttpStatus.PRECONDITION_REQUIRED;
            case NETWORK_CHANGED, STALE_REPORT -> HttpStatus.CONFLICT;
            case STATE_INVALID -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    private static String verificationOutcome(WebRtcVerificationOutcome outcome) {
        return switch (outcome) {
            case VERIFIED -> "matched";
            case VERIFICATION_PENDING -> "pending";
            case IP_FAMILY_INCOMPLETE -> "family_incomplete";
            case IP_MISMATCH -> "mismatch";
            case VERIFICATION_FAILED, VERIFICATION_REQUIRED -> "empty";
            case VERIFICATION_TIMEOUT -> "timeout";
            case NETWORK_CHANGED -> "network_changed";
            case STALE_REPORT -> "stale";
            case STATE_INVALID -> "invalid";
        };
    }

    private static String metricPlatform(AuthClientPlatform platform) {
        return platform == AuthClientPlatform.ANDROID ? "android" : "h5";
    }

    private void recordStartTransition(
            RiskScope scope,
            AuthClientPlatform platform,
            PreAuthAccess access,
            WebRtcVerificationDecision decision) {
        if (access.state().webRtcPhase() == PreAuthWebRtcPhase.REQUIRED
                && decision.outcome() == WebRtcVerificationOutcome.VERIFICATION_PENDING) {
            metrics.transition(
                    scope,
                    "required_started",
                    metricPlatform(platform),
                    "none",
                    properties.mode());
        } else if (access.state().webRtcPhase() == PreAuthWebRtcPhase.REQUIRED
                && decision.failureReason() == PreAuthWebRtcFailureReason.START_TIMEOUT) {
            metrics.transition(
                    scope,
                    "required_timeout",
                    metricPlatform(platform),
                    "start_timeout",
                    properties.mode());
        }
    }

    private void recordReportTransition(
            RiskScope scope,
            AuthClientPlatform platform,
            PreAuthAccess access,
            WebRtcVerificationDecision decision) {
        String metricPlatform = metricPlatform(platform);
        if (access.state().webRtcPhase() == PreAuthWebRtcPhase.PENDING
                && decision.outcome() == WebRtcVerificationOutcome.VERIFIED) {
            metrics.transition(
                    scope,
                    "pending_verified",
                    metricPlatform,
                    "none",
                    properties.mode());
            return;
        }
        if (access.state().webRtcPhase() == PreAuthWebRtcPhase.PENDING
                && (decision.outcome() == WebRtcVerificationOutcome.VERIFICATION_FAILED
                || decision.outcome() == WebRtcVerificationOutcome.VERIFICATION_TIMEOUT
                || decision.outcome() == WebRtcVerificationOutcome.IP_FAMILY_INCOMPLETE
                || decision.outcome() == WebRtcVerificationOutcome.IP_MISMATCH)) {
            metrics.transition(
                    scope,
                    "pending_failed",
                    metricPlatform,
                    failureReason(decision.failureReason()),
                    properties.mode());
            return;
        }
        if (decision.outcome() == WebRtcVerificationOutcome.STALE_REPORT) {
            metrics.transition(
                    scope,
                    "stale_report",
                    metricPlatform,
                    "stale",
                    properties.mode());
        }
    }

    private static String failureReason(PreAuthWebRtcFailureReason reason) {
        return reason == null ? "none" : reason.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String code(WebRtcVerificationOutcome outcome) {
        return switch (outcome) {
            case VERIFIED -> "WEBRTC_VERIFIED";
            case VERIFICATION_PENDING -> "WEBRTC_VERIFICATION_PENDING";
            case VERIFICATION_REQUIRED -> "WEBRTC_VERIFICATION_REQUIRED";
            case VERIFICATION_FAILED -> "WEBRTC_VERIFICATION_FAILED";
            case VERIFICATION_TIMEOUT -> "WEBRTC_VERIFICATION_TIMEOUT";
            case IP_FAMILY_INCOMPLETE -> "WEBRTC_IP_FAMILY_INCOMPLETE";
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
            case IP_FAMILY_INCOMPLETE ->
                    "未获取到与当前 HTTP 连接同协议族的 WebRTC 公网候选，当前会话已停止访问。";
            case IP_MISMATCH ->
                    "检测到 WebRTC IP 与当前 HTTP IP 不一致，当前会话已停止访问。";
            case NETWORK_CHANGED -> "检测期间网络环境发生变化，请读取最新探测状态。";
            case STALE_REPORT -> "该 WebRTC Report 已过期，请读取最新探测状态。";
            case STATE_INVALID -> "WebRTC 校验状态暂时不可用。";
        };
    }

    private static String reportPath(RiskScope scope) {
        return scope == RiskScope.ADMIN
                ? "/api/admin/_edge/webrtc/report"
                : "/api/_edge/webrtc/report";
    }

    private static <T> ResponseEntity<T> noStore(ResponseEntity<T> response) {
        return ResponseEntity.status(response.getStatusCode())
                .header(
                        HttpHeaders.CACHE_CONTROL,
                        CacheControl.noStore().getHeaderValue())
                .body(response.getBody());
    }

    /**
     * 接收客户端一次性上报的公网 host/srflx 候选集合，不允许携带 HTTP IP 或匹配状态。
     */
    public static final class WebRtcReportRequest {

        @NotBlank
        @Pattern(regexp = "^[1-9][0-9]{0,18}$")
        private final String probeGeneration;

        @NotNull
        @Size(max = 8)
        private final List<@NotBlank @Size(max = 64) String> webRtcIps;

        @JsonCreator
        public WebRtcReportRequest(
                @JsonProperty("probeGeneration") String probeGeneration,
                @JsonProperty("webRtcIps") List<String> webRtcIps) {
            this.probeGeneration = probeGeneration;
            this.webRtcIps = webRtcIps == null
                    ? null
                    : List.copyOf(webRtcIps);
        }

        public List<String> webRtcIps() {
            return webRtcIps;
        }

        public String probeGeneration() {
            return probeGeneration;
        }

        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            // HTTP IP 与匹配状态只能由可信服务端计算，任何额外字段都拒绝而不是静默忽略。
            throw new IllegalArgumentException(
                    "WebRTC report contains an unsupported field.");
        }
    }

    /**
     * 返回当前四态、固定 STUN 清单和本作用域 Report 路径；完整 IP 只在失败详情中返回。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WebRtcStartResponse(
            String mode,
            String verificationState,
            boolean probeRequired,
            Boolean webRtcStatus,
            String probeGeneration,
            Instant pendingUntil,
            long pendingRemainingMillis,
            long timeoutMillis,
            long reportGraceMillis,
            List<String> stunUrls,
            String reportPath,
            String code,
            String message,
            String httpIp,
            List<String> webRtcIps,
            Boolean retryable) {
    }

    /**
     * 返回后端计算的 WebRTC 校验结论；成功响应不回显 IP，失败详情仅供当前无缓存页面展示。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WebRtcVerificationResponse(
            String code,
            String message,
            Boolean webRtcStatus,
            String verificationState,
            String httpIp,
            List<String> webRtcIps,
            boolean retryable,
            Instant timestamp) {
    }
}
