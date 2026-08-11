package com.example.temperate.web.risk;

import com.example.temperate.service.risk.challenge.RiskChallengeService;
import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.domain.RiskDecision;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.observability.WebRtcMetrics;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthBootstrapOutcome;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcPhase;
import com.example.temperate.service.risk.preauth.service.PreAuthRiskBootstrapService;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.web.admin.transport.AdminCookieWriter;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import com.example.temperate.web.auth.session.transport.AuthClientPlatform;
import com.example.temperate.web.risk.webrtc.WebRtcVerificationTransport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供普通与管理员 PreAuth Bootstrap 以及 WAF Challenge 验证后的同源完成入口。
 *
 * <p>H5 原始 PreAuth Token 只写入 Host-only HttpOnly Cookie；Challenge 完成端点只接受顶层 GET，
 * 原子消费引用后返回同域固定页面，不自动重放原始非幂等请求。</p>
 */
@RestController
@Tag(
        name = "安全-网络风险边缘流程",
        description = "为普通 H5、管理员 H5 和 Android 建立 PreAuth，并闭合 Cloudflare WAF Challenge；不返回 IP 信用分、坐标或供应商数据。")
public final class NetworkRiskEdgeController {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(NetworkRiskEdgeController.class);

    private static final String DEVICE_HEADER = "X-Device-Installation-Id";
    private static final String PLATFORM_HEADER = "X-Client-Platform";
    private static final String USER_COMPLETE_PATH = "/pages/risk/challenge-complete";
    private static final String ADMIN_COMPLETE_PATH = "/pages/risk/challenge-complete";

    private final PreAuthRiskBootstrapService bootstrapService;
    private final PreAuthService preAuthService;
    private final RiskChallengeService challengeService;
    private final RiskRequestContextResolver contextResolver;
    private final PreAuthTransport transport;
    private final NetworkRiskProperties properties;
    private final AuthCookieWriter authCookieWriter;
    private final AdminCookieWriter adminCookieWriter;
    private final WebRtcVerificationTransport webRtcTransport;
    private final WebRtcMetrics webRtcMetrics;
    private final Clock clock;

    public NetworkRiskEdgeController(
            PreAuthRiskBootstrapService bootstrapService,
            PreAuthService preAuthService,
            RiskChallengeService challengeService,
            RiskRequestContextResolver contextResolver,
            PreAuthTransport transport,
            NetworkRiskProperties properties,
            AuthCookieWriter authCookieWriter,
            AdminCookieWriter adminCookieWriter,
            WebRtcVerificationTransport webRtcTransport,
            WebRtcMetrics webRtcMetrics,
            Clock clock) {
        this.bootstrapService = Objects.requireNonNull(bootstrapService);
        this.preAuthService = Objects.requireNonNull(preAuthService);
        this.challengeService = Objects.requireNonNull(challengeService);
        this.contextResolver = Objects.requireNonNull(contextResolver);
        this.transport = Objects.requireNonNull(transport);
        this.properties = Objects.requireNonNull(properties);
        this.authCookieWriter = Objects.requireNonNull(authCookieWriter);
        this.adminCookieWriter = Objects.requireNonNull(adminCookieWriter);
        this.webRtcTransport = Objects.requireNonNull(webRtcTransport);
        this.webRtcMetrics = Objects.requireNonNull(webRtcMetrics);
        this.clock = Objects.requireNonNull(clock);
    }

    @PostMapping("/api/_edge/pre-auth")
    @Operation(summary = "建立或滑动续期普通用户 PreAuth")
    public ResponseEntity<BootstrapResponse> bootstrapUser(
            @RequestHeader(DEVICE_HEADER) String device,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platform,
            HttpServletRequest request,
            HttpServletResponse response) {
        return bootstrap(RiskScope.USER, device, platform, request, response);
    }

    @PostMapping("/api/admin/_edge/pre-auth")
    @Operation(summary = "建立或滑动续期管理员 PreAuth")
    public ResponseEntity<BootstrapResponse> bootstrapAdmin(
            @RequestHeader(DEVICE_HEADER) String device,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platform,
            HttpServletRequest request,
            HttpServletResponse response) {
        return bootstrap(RiskScope.ADMIN, device, platform, request, response);
    }

    @GetMapping("/api/_edge/risk-challenge")
    @Operation(summary = "消费普通用户一次性 WAF Challenge 引用")
    public ResponseEntity<ChallengeCompletionResponse> completeUserChallenge(
            @RequestParam("ref") String reference,
            HttpServletRequest request) {
        return completeChallenge(
                RiskScope.USER,
                reference,
                USER_COMPLETE_PATH,
                request);
    }

    @GetMapping("/api/admin/_edge/risk-challenge")
    @Operation(summary = "消费管理员一次性 WAF Challenge 引用")
    public ResponseEntity<ChallengeCompletionResponse> completeAdminChallenge(
            @RequestParam("ref") String reference,
            HttpServletRequest request) {
        return completeChallenge(
                RiskScope.ADMIN,
                reference,
                ADMIN_COMPLETE_PATH,
                request);
    }

    private ResponseEntity<BootstrapResponse> bootstrap(
            RiskScope scope,
            String device,
            String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        if (properties.mode() == NetworkRiskMode.DISABLED) {
            // 关闭模式不创建依赖进程内临时密钥的持久状态，让 localhost 与旧客户端保持原协议。
            return ResponseEntity.ok(new BootstrapResponse(
                    "DISABLED",
                    null,
                    null,
                    clock.instant(),
                    false,
                    null,
                    null));
        }
        TrustedNetworkObservation observation = contextResolver.resolve(request)
                .orElse(null);
        if (observation == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new BootstrapResponse(
                            "UNAVAILABLE",
                            "RISK_CONTEXT_UNAVAILABLE",
                            null,
                            clock.instant(),
                            false,
                            null,
                            null));
        }
        String existing = transport.read(request, scope);
        boolean h5 = AuthClientPlatform.fromHeader(platformHeader)
                == AuthClientPlatform.H5;
        final PreAuthBootstrapOutcome outcome;
        try {
            outcome = bootstrapService.bootstrap(
                            scope,
                            existing,
                            device,
                            observation,
                            "1".equals(request.getHeader(
                                    PreAuthTransport.RESET_HEADER)))
                    .block(properties.lookupTimeout());
        } catch (DataAccessException exception) {
            return riskAssessmentUnavailable(scope, exception);
        } catch (IllegalStateException exception) {
            if (!isAssessmentInfrastructureFailure(exception)) {
                throw exception;
            }
            return riskAssessmentUnavailable(scope, exception);
        }
        if (outcome == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new BootstrapResponse(
                            "UNAVAILABLE",
                            "RISK_ASSESSMENT_UNAVAILABLE",
                            null,
                            clock.instant(),
                            false,
                            null,
                            null));
        }
        webRtcTransport.write(response, outcome.issue());
        if (outcome.issue().webRtcPhase() == PreAuthWebRtcPhase.REQUIRED) {
            webRtcMetrics.transition(
                    scope,
                    "required_created",
                    h5 ? "h5" : "android",
                    "none",
                    properties.mode());
        }
        if (h5) {
            if (outcome.reauthenticationRequired()) {
                // v1 Cookie 或显式重置必须清除不可由前端读取的旧登录 Cookie，确保迁移后统一重新登录。
                clearAuthenticationCookies(scope, response);
            }
            transport.writeCookie(
                    response,
                    scope,
                    outcome.issue().rawToken());
        }
        if (properties.mode() == NetworkRiskMode.OBSERVE
                || outcome.assessment().decision() == RiskDecision.ALLOW) {
            return ResponseEntity.ok(new BootstrapResponse(
                    "READY",
                    null,
                    h5 ? null : outcome.issue().rawToken(),
                    outcome.issue().expiresAt(),
                    outcome.reauthenticationRequired(),
                    null,
                    null));
        }
        if (outcome.assessment().decision() == RiskDecision.BLOCK) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new BootstrapResponse(
                            "BLOCKED",
                            "RISK_BLOCKED",
                            h5 ? null : outcome.issue().rawToken(),
                            outcome.issue().expiresAt(),
                            outcome.reauthenticationRequired(),
                            null,
                            null));
        }
        if (outcome.challenge() == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new BootstrapResponse(
                            "CHALLENGE_UNAVAILABLE",
                            "RISK_CHALLENGE_UNAVAILABLE",
                            h5 ? null : outcome.issue().rawToken(),
                            outcome.issue().expiresAt(),
                            outcome.reauthenticationRequired(),
                            null,
                            null));
        }
        String challengePath = scope == RiskScope.ADMIN
                ? "/api/admin/_edge/risk-challenge"
                : "/api/_edge/risk-challenge";
        return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED)
                .body(new BootstrapResponse(
                        "CHALLENGE_REQUIRED",
                        "RISK_CHALLENGE_REQUIRED",
                        h5 ? null : outcome.issue().rawToken(),
                        outcome.challenge().expiresAt(),
                        outcome.reauthenticationRequired(),
                        outcome.challenge().reference(),
                        challengePath));
    }

    private void clearAuthenticationCookies(
            RiskScope scope,
            HttpServletResponse response) {
        if (scope == RiskScope.ADMIN) {
            adminCookieWriter.clearRegistration(response);
            adminCookieWriter.clearLogin(response);
            adminCookieWriter.clearSession(response);
            return;
        }
        authCookieWriter.clearSession(response);
    }

    private ResponseEntity<ChallengeCompletionResponse> completeChallenge(
            RiskScope scope,
            String reference,
            String completionPath,
            HttpServletRequest request) {
        try {
            String rawPreAuth = transport.read(request, scope);
            PreAuthAccess access = preAuthService.resolveChallengeNavigation(
                            scope,
                            rawPreAuth)
                    .orElse(null);
            if (access == null) {
                return challengeCompletionError(
                        HttpStatus.FORBIDDEN,
                        "RISK_CHALLENGE_INVALID");
            }
            TrustedNetworkObservation observation = contextResolver.resolve(request)
                    .orElse(null);
            if (observation == null) {
                return challengeCompletionError(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "RISK_CONTEXT_UNAVAILABLE");
            }
            if (!challengeService.consumeAndTrust(
                    access,
                    reference,
                    observation)) {
                return challengeCompletionError(
                        HttpStatus.FORBIDDEN,
                        "RISK_CHALLENGE_INVALID");
            }
            return ResponseEntity.status(HttpStatus.SEE_OTHER)
                    .location(URI.create(completionPath))
                    .cacheControl(CacheControl.noStore().cachePrivate())
                    .build();
        } catch (DataAccessException exception) {
            // Redis 等持久化基础设施故障不能伪装成用户挑战失败，也不能泄露底层异常消息。
            LOGGER.warn(
                    "event=risk_challenge_unavailable scope={} exceptionClass={}",
                    scope,
                    exception.getClass().getSimpleName());
            return challengeCompletionError(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "RISK_CHALLENGE_UNAVAILABLE");
        }
    }

    private ResponseEntity<ChallengeCompletionResponse> challengeCompletionError(
            HttpStatus status,
            String code) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(new ChallengeCompletionResponse(
                        code,
                        "The risk challenge could not be completed.",
                        clock.instant()));
    }

    private ResponseEntity<BootstrapResponse> riskAssessmentUnavailable(
            RiskScope scope,
            RuntimeException exception) {
        // 只把明确的存储或等待超时归为基础设施不可用，其他程序异常继续交给全局 500 处理。
        LOGGER.warn(
                "event=risk_assessment_unavailable scope={} exceptionClass={}",
                scope,
                exception.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new BootstrapResponse(
                        "UNAVAILABLE",
                        "RISK_ASSESSMENT_UNAVAILABLE",
                        null,
                        clock.instant(),
                        false,
                        null,
                        null));
    }

    private static boolean isAssessmentInfrastructureFailure(
            IllegalStateException exception) {
        String message = exception.getMessage();
        return message != null
                && (message.startsWith("Timeout on blocking read")
                        || message.equals("Created PreAuth state is unavailable."));
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(
                HttpHeaders.CACHE_CONTROL,
                CacheControl.noStore().cachePrivate().getHeaderValue());
    }

    /**
     * 返回 PreAuth 就绪状态；仅 Android 响应携带原始 Token，H5 字段固定为空。
     */
    public record BootstrapResponse(
            String status,
            String code,
            String preAuthToken,
            Instant expiresAt,
            boolean reauthenticationRequired,
            String challengeRef,
            String challengePath) {
    }

    /**
     * 返回风险挑战导航的稳定失败合同，不包含 Token、引用或网络识别信息。
     */
    public record ChallengeCompletionResponse(
            String code,
            String message,
            Instant timestamp) {
    }
}
