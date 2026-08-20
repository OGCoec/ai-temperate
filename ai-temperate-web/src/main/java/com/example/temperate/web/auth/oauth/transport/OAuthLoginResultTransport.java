package com.example.temperate.web.auth.oauth.transport;

import com.example.temperate.service.auth.login.dto.result.LoginFlowStatus;
import com.example.temperate.service.auth.login.dto.result.LoginResult;
import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthIssue;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * 将 OAuth 登录完成结果按 H5 Cookie 或 Android 响应体协议安全交付，并复用现有 PreAuth 会话提升。
 *
 * <p>OAuth 不得形成绕过普通登录风险绑定的第二条会话签发通道；正式会话必须先完成 PreAuth 旋转，H5 才写
 * AT/RT/CSRF Cookie。TOTP 尚未完成时只交付短期挑战，不提升或创建正式会话。</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.oauth", name = "enabled", havingValue = "true")
public final class OAuthLoginResultTransport {

    private final AuthCookieWriter cookieWriter;
    private final AuthFlowCookieWriter flowCookieWriter;
    private final PreAuthService preAuthService;
    private final PreAuthTransport preAuthTransport;
    private final RiskRequestContextResolver riskContextResolver;
    private final NetworkRiskProperties networkRiskProperties;
    private final WebRtcVerificationTransport webRtcTransport;

    public OAuthLoginResultTransport(
            AuthCookieWriter cookieWriter,
            AuthFlowCookieWriter flowCookieWriter,
            PreAuthService preAuthService,
            PreAuthTransport preAuthTransport,
            RiskRequestContextResolver riskContextResolver,
            NetworkRiskProperties networkRiskProperties,
            WebRtcVerificationTransport webRtcTransport) {
        this.cookieWriter = Objects.requireNonNull(cookieWriter);
        this.flowCookieWriter = Objects.requireNonNull(flowCookieWriter);
        this.preAuthService = Objects.requireNonNull(preAuthService);
        this.preAuthTransport = Objects.requireNonNull(preAuthTransport);
        this.riskContextResolver = Objects.requireNonNull(riskContextResolver);
        this.networkRiskProperties = Objects.requireNonNull(networkRiskProperties);
        this.webRtcTransport = Objects.requireNonNull(webRtcTransport);
    }

    public OAuthLoginResponse write(
            LoginResult result,
            AuthClientPlatform platform,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, private");
        if (!result.isAuthenticated()) {
            if (platform == AuthClientPlatform.H5) {
                flowCookieWriter.writeTotpLoginFlow(response, result.getTotpFlowToken());
            }
            return response(result, platform, null);
        }
        PreAuthIssue preAuth = promotePreAuth(
                request, response, platform, result.getRefreshToken());
        if (platform == AuthClientPlatform.H5) {
            // PreAuth 提升必须先于 Cookie 交付，防止风险状态旋转失败时浏览器已经得到正式凭据。
            cookieWriter.writeSession(
                    response,
                    result.getAccessToken(),
                    result.getRefreshToken(),
                    result.getCsrfToken());
        }
        return response(result, platform, preAuth);
    }

    private PreAuthIssue promotePreAuth(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthClientPlatform platform,
            String refreshToken) {
        if (networkRiskProperties.mode() == NetworkRiskMode.DISABLED) {
            return null;
        }
        PreAuthAccess access = verifiedPreAuthAccess(request);
        TrustedNetworkObservation observation = riskContextResolver.resolve(request)
                .orElse(null);
        if (access == null || observation == null) {
            if (networkRiskProperties.mode() == NetworkRiskMode.OBSERVE) {
                return null;
            }
            throw new SessionAuthenticationException(
                    SessionAuthenticationErrorCode.PREAUTH_REQUIRED,
                    "PreAuth is required after OAuth login.",
                    false);
        }
        PreAuthIssue issue = preAuthService.promoteAuthenticated(
                access,
                RiskSessionType.USER_REFRESH,
                refreshToken,
                observation.observedAt());
        webRtcTransport.write(response, issue);
        if (platform == AuthClientPlatform.H5) {
            preAuthTransport.writeCookie(response, RiskScope.USER, issue.rawToken());
        }
        return issue;
    }

    private static PreAuthAccess verifiedPreAuthAccess(HttpServletRequest request) {
        Object value = request.getAttribute(NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE);
        return value instanceof PreAuthAccess access ? access : null;
    }

    private static OAuthLoginResponse response(
            LoginResult result,
            AuthClientPlatform platform,
            PreAuthIssue preAuth) {
        boolean android = platform == AuthClientPlatform.ANDROID;
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
                result.getAttemptsRemaining());
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
            Integer attemptsRemaining) {
    }
}
