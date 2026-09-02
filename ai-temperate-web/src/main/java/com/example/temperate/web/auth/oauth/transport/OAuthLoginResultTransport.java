package com.example.temperate.web.auth.oauth.transport;

import com.example.temperate.service.auth.login.dto.result.LoginFlowStatus;
import com.example.temperate.service.auth.login.dto.result.LoginResult;
import com.example.temperate.service.auth.oauth.flow.ProtectedOAuthFlowAccess;
import com.example.temperate.service.auth.oauth.webrtc.OAuthWebRtcSessionVerdictService;
import com.example.temperate.service.auth.oauth.webrtc.OAuthWebRtcSessionVerdictService.PendingSession;
import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthIssue;
import com.example.temperate.service.risk.preauth.domain.PreAuthRequiredException;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.web.auth.diagnostic.filter.AuthRequestTraceFilter;
import com.example.temperate.web.auth.flow.transport.AuthFlowCookieWriter;
import com.example.temperate.web.auth.session.transport.AuthClientPlatform;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import com.example.temperate.web.risk.NetworkRiskInterceptor;
import com.example.temperate.web.risk.PreAuthTransport;
import com.example.temperate.web.risk.RiskRequestContextResolver;
import com.example.temperate.web.risk.webrtc.WebRtcVerificationTransport;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * 将 OAuth 登录完成结果按 H5 Cookie 或 Android 响应体协议安全交付，并复用现有 PreAuth 会话提升。
 *
 * <p>OAuth 不得形成绕过普通登录风险绑定的第二条会话签发通道；严格旧客户端要求 VERIFIED，新 H5
 * attempt 则在同一 Redis 原子边界写入十五秒 PENDING Session 与 PreAuth 绑定后才写 Cookie。TOTP 尚未
 * 完成时只交付短期挑战，不提升或创建正式会话。</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.oauth", name = "enabled", havingValue = "true")
public final class OAuthLoginResultTransport {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(OAuthLoginResultTransport.class);

    private final AuthCookieWriter cookieWriter;
    private final AuthFlowCookieWriter flowCookieWriter;
    private final PreAuthService preAuthService;
    private final PreAuthTransport preAuthTransport;
    private final RiskRequestContextResolver riskContextResolver;
    private final NetworkRiskProperties networkRiskProperties;
    private final WebRtcVerificationTransport webRtcTransport;
    private final OAuthWebRtcSessionVerdictService oauthVerdictService;

    public OAuthLoginResultTransport(
            AuthCookieWriter cookieWriter,
            AuthFlowCookieWriter flowCookieWriter,
            PreAuthService preAuthService,
            PreAuthTransport preAuthTransport,
            RiskRequestContextResolver riskContextResolver,
            NetworkRiskProperties networkRiskProperties,
            WebRtcVerificationTransport webRtcTransport,
            OAuthWebRtcSessionVerdictService oauthVerdictService) {
        this.cookieWriter = Objects.requireNonNull(cookieWriter);
        this.flowCookieWriter = Objects.requireNonNull(flowCookieWriter);
        this.preAuthService = Objects.requireNonNull(preAuthService);
        this.preAuthTransport = Objects.requireNonNull(preAuthTransport);
        this.riskContextResolver = Objects.requireNonNull(riskContextResolver);
        this.networkRiskProperties = Objects.requireNonNull(networkRiskProperties);
        this.webRtcTransport = Objects.requireNonNull(webRtcTransport);
        this.oauthVerdictService = Objects.requireNonNull(oauthVerdictService);
    }

    public OAuthLoginResponse write(
            LoginResult result,
            AuthClientPlatform platform,
            HttpServletRequest request,
            HttpServletResponse response) {
        return write(result, platform, null, null, null,
                request.getHeader("X-Device-Installation-Id"), request, response);
    }

    public OAuthLoginResponse write(
            LoginResult result,
            AuthClientPlatform platform,
            ProtectedOAuthFlowAccess flow,
            String webRtcAttemptId,
            String webRtcGeneration,
            String rawDeviceId,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, private");
        if (!result.isAuthenticated()) {
            if (platform == AuthClientPlatform.H5) {
                flowCookieWriter.writeTotpLoginFlow(response, result.getTotpFlowToken());
            }
            return response(result, platform, null);
        }
        Promotion promotion = promotePreAuth(
                request,
                response,
                platform,
                flow,
                webRtcAttemptId,
                webRtcGeneration,
                rawDeviceId,
                result.getRefreshToken());
        PreAuthIssue preAuth = promotion.preAuth();
        if (platform == AuthClientPlatform.H5) {
            // PreAuth 提升必须先于 Cookie 交付，防止风险状态旋转失败时浏览器已经得到正式凭据。
            cookieWriter.writeSession(
                    response,
                    result.getAccessToken(),
                    result.getRefreshToken(),
                    result.getCsrfToken());
        }
        return response(result, platform, promotion);
    }

    private Promotion promotePreAuth(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthClientPlatform platform,
            ProtectedOAuthFlowAccess flow,
            String webRtcAttemptId,
            String webRtcGeneration,
            String rawDeviceId,
            String refreshToken) {
        if (networkRiskProperties.mode() == NetworkRiskMode.DISABLED) {
            return new Promotion(null, null);
        }
        PreAuthAccess access = verifiedPreAuthAccess(request);
        TrustedNetworkObservation observation = riskContextResolver.resolve(request)
                .orElse(null);
        if (access == null || observation == null) {
            if (networkRiskProperties.mode() == NetworkRiskMode.OBSERVE) {
                return new Promotion(null, null);
            }
            throw new SessionAuthenticationException(
                    SessionAuthenticationErrorCode.PREAUTH_REQUIRED,
                    "PreAuth is required after OAuth login.",
                    false);
        }
        PreAuthIssue issue;
        Instant verdictDeadline = null;
        try {
            boolean pendingOAuthVerdict = platform == AuthClientPlatform.H5
                    && networkRiskProperties.mode() == NetworkRiskMode.ENFORCE
                    && webRtcAttemptId != null
                    && !webRtcAttemptId.isBlank();
            if (pendingOAuthVerdict) {
                if (flow == null || webRtcGeneration == null || rawDeviceId == null) {
                    throw new PreAuthRequiredException();
                }
                // Refresh Session 已创建但 Cookie 尚未写出；Lua 失败时不会向浏览器交付任何正式凭据。
                PendingSession pending = oauthVerdictService.issuePendingOAuthVerdict(
                        flow,
                        access,
                        webRtcAttemptId,
                        webRtcGeneration,
                        refreshToken,
                        rawDeviceId,
                        observation.observedAt());
                issue = pending.preAuth();
                verdictDeadline = pending.verdictDeadlineAt();
            } else if (platform == AuthClientPlatform.H5
                    && networkRiskProperties.mode() == NetworkRiskMode.ENFORCE) {
                issue = preAuthService.promoteAuthenticatedAfterWebRtcVerified(
                            access,
                            RiskSessionType.USER_REFRESH,
                            refreshToken,
                            observation.clientIp(),
                            observation.observedAt());
            } else {
                issue = preAuthService.promoteAuthenticated(
                        access,
                        RiskSessionType.USER_REFRESH,
                        refreshToken,
                        observation.observedAt());
            }
        } catch (PreAuthRequiredException exception) {
            if (platform == AuthClientPlatform.H5
                    && networkRiskProperties.mode() == NetworkRiskMode.ENFORCE) {
                LOGGER.warn(
                        "event=oauth_completion_blocked_by_webrtc traceId={} "
                                + "clientRequestId={} pageInstanceId={} probeRunId={} path={}",
                        diagnosticAttribute(request, AuthRequestTraceFilter.TRACE_ATTRIBUTE),
                        diagnosticAttribute(request, AuthRequestTraceFilter.CLIENT_REQUEST_ATTRIBUTE),
                        diagnosticAttribute(request, AuthRequestTraceFilter.PAGE_INSTANCE_ATTRIBUTE),
                        diagnosticAttribute(request, AuthRequestTraceFilter.WEBRTC_PROBE_RUN_ATTRIBUTE),
                        "/api/auth/oauth2/complete");
                throw new SessionAuthenticationException(
                        SessionAuthenticationErrorCode.PREAUTH_REQUIRED,
                        "WebRTC verification is required before OAuth completion.",
                        false);
            }
            throw exception;
        }
        webRtcTransport.write(response, issue);
        if (platform == AuthClientPlatform.H5) {
            preAuthTransport.writeCookie(response, RiskScope.USER, issue.rawToken());
        }
        return new Promotion(issue, verdictDeadline);
    }

    private static PreAuthAccess verifiedPreAuthAccess(HttpServletRequest request) {
        Object value = request.getAttribute(NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE);
        return value instanceof PreAuthAccess access ? access : null;
    }

    private static String diagnosticAttribute(
            HttpServletRequest request,
            String attributeName) {
        Object value = request.getAttribute(attributeName);
        if (!(value instanceof String text)
                || text.length() > 128
                || !text.matches("^[A-Za-z0-9._:-]{1,128}$")) {
            return "absent";
        }
        return text;
    }

    private static OAuthLoginResponse response(
            LoginResult result,
            AuthClientPlatform platform,
            Promotion promotion) {
        boolean android = platform == AuthClientPlatform.ANDROID;
        PreAuthIssue preAuth = promotion == null ? null : promotion.preAuth();
        return new OAuthLoginResponse(
                result.getStatus(),
                result.getPublicId(),
                result.getDisplayName(),
                android ? result.getAccessToken() : null,
                android ? result.getRefreshToken() : null,
                android ? result.getCsrfToken() : null,
                android && preAuth != null ? preAuth.rawToken() : null,
                result.getRefreshExpiresAt(),
                android ? result.getTotpFlowToken() : null,
                result.getTotpExpiresAt(),
                result.getAttemptsRemaining(),
                promotion != null && promotion.verdictDeadlineAt() != null
                        ? "PENDING" : null,
                promotion == null ? null : promotion.verdictDeadlineAt());
    }

    /** 把新 PreAuth 与可选的异步裁决截止时间作为同一传输结果，避免 Cookie 先于状态机写出。 */
    private record Promotion(PreAuthIssue preAuth, Instant verdictDeadlineAt) {
    }

    /**
     * 表示 OAuth 最终登录结果；只有 Android 响应序列化正式令牌或 TOTP Flow Token。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OAuthLoginResponse(
            LoginFlowStatus status,
            String publicUserId,
            String displayName,
            String accessToken,
            String refreshToken,
            String csrfToken,
            String preAuthToken,
            Instant refreshExpiresAt,
            String totpFlowToken,
            Instant totpExpiresAt,
            Integer attemptsRemaining,
            String webRtcVerdict,
            Instant webRtcVerdictDeadlineAt) {
    }
}
