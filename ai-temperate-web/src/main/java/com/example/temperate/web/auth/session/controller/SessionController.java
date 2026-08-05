package com.example.temperate.web.auth.session.controller;

import com.example.temperate.service.auth.session.authentication.dto.command.LogoutCommand;
import com.example.temperate.service.auth.session.authentication.dto.command.SessionBootstrapCommand;
import com.example.temperate.service.auth.session.authentication.dto.result.SessionAuthenticationResult;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.service.auth.session.authentication.service.SessionAuthenticationService;
import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.web.auth.interceptor.UserSessionAuthenticationInterceptor;
import com.example.temperate.web.auth.api.WebInvalidInputException;
import com.example.temperate.web.auth.session.transport.AuthClientPlatform;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthSessionBinding;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.web.risk.PreAuthTransport;
import com.example.temperate.web.risk.NetworkRiskInterceptor;
import com.example.temperate.web.risk.webrtc.WebRtcVerificationTransport;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * H5 与 Android 登录会话恢复和退出的 HTTP 接口控制器。
 *
 * <p>用途：恢复 H5 新标签页会话，以及撤销当前设备或全部设备的 Refresh Session。</p>
 *
 * <p>普通 API 的 RT-first 校验与同请求 AT 续签由用户会话拦截器负责；本控制器不再提供显式 refresh 接口。</p>
 */
@RestController
@RequestMapping("/api/auth/session")
@Tag(
        name = "认证-会话与令牌",
        description = "管理 H5 会话恢复以及 H5、Android 当前设备和全部设备退出；普通 API 的 RT-first 认证与"
                + "同请求 AT 续签由会话拦截器处理，本控制器不提供显式刷新接口。")
public final class SessionController {

    private static final String DEVICE_HEADER = "X-Device-Installation-Id";
    private static final String PLATFORM_HEADER = "X-Client-Platform";
    private static final String CSRF_HEADER = "X-CSRF-Token";

    private final SessionAuthenticationService sessionService;
    private final AuthCookieWriter cookieWriter;
    private final PreAuthService preAuthService;
    private final PreAuthTransport preAuthTransport;
    private final NetworkRiskProperties networkRiskProperties;
    private final WebRtcVerificationTransport webRtcTransport;

    public SessionController(
            SessionAuthenticationService sessionService,
            AuthCookieWriter cookieWriter,
            PreAuthService preAuthService,
            PreAuthTransport preAuthTransport,
            NetworkRiskProperties networkRiskProperties,
            WebRtcVerificationTransport webRtcTransport) {
        this.sessionService = sessionService;
        this.cookieWriter = cookieWriter;
        this.preAuthService = preAuthService;
        this.preAuthTransport = preAuthTransport;
        this.networkRiskProperties = networkRiskProperties;
        this.webRtcTransport = webRtcTransport;
    }

    @PostMapping("/bootstrap")
    @Operation(
            summary = "恢复 H5 新标签页会话",
            description = "使用同一个 HttpOnly RT 恢复 AT，生成新的 CSRF 并使旧 CSRF 立即失效；RT 原文保持不变。")
    public SessionResponse bootstrap(
            @RequestBody(required = false) SessionRequest body,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        AuthClientPlatform platform = AuthClientPlatform.fromHeader(platformHeader);
        if (platform != AuthClientPlatform.H5) {
            throw new WebInvalidInputException();
        }
        // bootstrap 只允许 H5：它依赖浏览器携带 SameSite RT Cookie，并由安全拦截器校验来源。
        String refreshToken = refreshToken(platform, body, request);
        SessionBootstrapCommand command = new SessionBootstrapCommand(
                accessToken(request), refreshToken, deviceId);
        PreAuthSessionBinding binding = userPreAuthBinding(request, refreshToken);
        SessionAuthenticationResult result = binding == null
                ? sessionService.bootstrap(command)
                : sessionService.bootstrap(command, binding);
        cookieWriter.writeSession(
                response,
                result.getAccessToken(),
                refreshToken,
                result.getCsrfToken());
        Object preAuth = request.getAttribute(
                NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE);
        if (preAuth instanceof PreAuthAccess access) {
            webRtcTransport.write(
                    response,
                    access.state().webRtcPhase(),
                    access.state().webRtcGeneration());
        }
        return response(result, platform);
    }

    @PostMapping("/logout")
    @Operation(
            summary = "退出当前设备",
            description = "撤销当前固定 RT，并从用户 RT 反向索引删除当前字段；H5 同时清除 AT、RT、XSRF 和用户 PreAuth Cookie。")
    public LogoutResponse logout(
            @RequestBody(required = false) SessionRequest body,
            @RequestHeader(value = DEVICE_HEADER, required = false) String deviceId,
            @RequestHeader(value = CSRF_HEADER, required = false) String csrfToken,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        AuthClientPlatform platform = AuthClientPlatform.fromHeader(platformHeader);
        String refreshToken = refreshToken(platform, body, request);
        sessionService.logout(new LogoutCommand(refreshToken, csrfToken, deviceId));
        preAuthService.revoke(
                RiskScope.USER,
                preAuthTransport.read(request, RiskScope.USER));
        if (platform == AuthClientPlatform.H5) {
            cookieWriter.clearSession(response);
            preAuthTransport.clearCookie(response, RiskScope.USER);
        }
        return new LogoutResponse(true);
    }

    /**
     * 使用 MVC 认证拦截器已经校验的用户主体撤销全部 Refresh Session，不让客户端指定撤销目标。
     * H5 只有在服务端撤销成功后才清理 Cookie，避免把本地清理误报为服务端全量撤销成功。
     */
    @PostMapping("/logout-all")
    @Operation(
            summary = "退出当前用户的所有设备",
            description = "使用已完成 RT-first 会话认证的主体确定当前用户，不接受请求体中的 userId；"
                    + "随后批量撤销该用户的全部 Refresh Session。H5 成功后清理浏览器认证与 PreAuth Cookie。")
    public LogoutResponse logoutAll(
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        SessionPrincipal principal = currentPrincipal(request);
        sessionService.logoutAllForUser(principal.userId());
        preAuthService.revoke(
                RiskScope.USER,
                preAuthTransport.read(request, RiskScope.USER));
        if (AuthClientPlatform.fromHeader(platformHeader) == AuthClientPlatform.H5) {
            cookieWriter.clearSession(response);
            preAuthTransport.clearCookie(response, RiskScope.USER);
        }
        return new LogoutResponse(true);
    }

    private static SessionResponse response(
            SessionAuthenticationResult result, AuthClientPlatform platform) {
        boolean android = platform == AuthClientPlatform.ANDROID;
        return new SessionResponse(
                result.getPrincipal().publicId(),
                result.getPrincipal().displayName(),
                android ? result.getAccessToken() : null,
                android ? result.getCsrfToken() : null,
                result.getRefreshExpiresAt());
    }

    private static String refreshToken(
            AuthClientPlatform platform, SessionRequest body, HttpServletRequest request) {
        if (platform == AuthClientPlatform.ANDROID) {
            // Android 不应依赖 Cookie，RT 由客户端安全存储后明确写入请求体。
            return body == null ? null : body.refreshToken();
        }
        // H5 不接受请求体 RT，避免 JavaScript 接触 HttpOnly 刷新凭据。
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (AuthCookieWriter.REFRESH_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static String accessToken(HttpServletRequest request) {
        // bootstrap 只允许 H5，因此仅从 HttpOnly access_token Cookie 读取可选旧 AT。
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (AuthCookieWriter.ACCESS_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static SessionPrincipal currentPrincipal(HttpServletRequest request) {
        Object principal = request.getAttribute(
                UserSessionAuthenticationInterceptor.PRINCIPAL_ATTRIBUTE);
        if (principal instanceof SessionPrincipal sessionPrincipal) {
            return sessionPrincipal;
        }
        throw new SessionAuthenticationException(
                SessionAuthenticationErrorCode.ACCESS_TOKEN_REQUIRED,
                "Access token is required.",
                true);
    }

    private PreAuthSessionBinding userPreAuthBinding(
            HttpServletRequest request,
            String rawRefreshToken) {
        Object value = request.getAttribute(
                NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE);
        if (!(value instanceof PreAuthAccess access)) {
            // NETWORK_RISK_MODE=DISABLED 时没有 PreAuth 上下文，保留本地开发的原有会话路径。
            return null;
        }
        try {
            return preAuthService.requireSessionBinding(
                    access,
                    RiskScope.USER,
                    RiskSessionType.USER_REFRESH,
                    rawRefreshToken);
        } catch (IllegalArgumentException exception) {
            if (networkRiskProperties.mode() == NetworkRiskMode.OBSERVE) {
                return null;
            }
            throw new SessionAuthenticationException(
                    SessionAuthenticationErrorCode.PREAUTH_REQUIRED,
                    "Authenticated PreAuth is no longer bound to this session.",
                    false,
                    exception);
        }
    }

    public record SessionRequest(String refreshToken) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionResponse(
            String publicUserId,
            String displayName,
            String accessToken,
            String csrfToken,
            Instant refreshExpiresAt) {
    }

    public record LogoutResponse(boolean loggedOut) {
    }
}
