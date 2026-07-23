package com.example.temperate.web.auth.session.controller;

import com.example.temperate.service.auth.session.authentication.dto.command.LogoutCommand;
import com.example.temperate.service.auth.session.authentication.dto.command.SessionAuthenticationCommand;
import com.example.temperate.service.auth.session.authentication.dto.command.SessionBootstrapCommand;
import com.example.temperate.service.auth.session.authentication.dto.result.SessionAuthenticationResult;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.service.auth.session.authentication.service.SessionAuthenticationService;
import com.example.temperate.web.auth.interceptor.AccessTokenAuthenticationInterceptor;
import com.example.temperate.web.auth.session.transport.AuthClientPlatform;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * H5 与 Android 登录会话刷新、恢复和退出的 HTTP 接口控制器。
 *
 * <p>用途：调用会话服务续期固定 RT、签发新 AT、恢复 H5 新标签页会话并撤销当前设备会话。</p>
 *
 * <p>传输安全边界：H5 只从 HttpOnly Cookie 读取 AT/RT 并重写 Cookie；Android 只从 Authorization 读取旧 AT、
 * 从请求体读取 RT。两端不互相回退，平台头只选择传输协议而非认证凭据。</p>
 */
@RestController
@RequestMapping("/api/auth/session")
@Tag(
        name = "认证-会话与令牌",
        description = "管理 H5 与 Android 登录会话：使用固定 Refresh Token 滑动续期并签发新的十分钟 Access Token，"
                + "H5 使用 HttpOnly Cookie 与 Spring CSRF，Android 使用 AndroidKeyStore 加密凭证，"
                + "并负责当前设备安全退出；不负责账号资料和业务权限管理。")
public final class SessionController {

    private static final String DEVICE_HEADER = "X-Device-Installation-Id";
    private static final String PLATFORM_HEADER = "X-Client-Platform";
    private static final String CSRF_HEADER = "X-CSRF-Token";

    private final SessionAuthenticationService sessionService;
    private final AuthCookieWriter cookieWriter;

    public SessionController(
            SessionAuthenticationService sessionService,
            AuthCookieWriter cookieWriter) {
        this.sessionService = sessionService;
        this.cookieWriter = cookieWriter;
    }

    @PostMapping("/refresh")
    @Operation(
            summary = "续期固定 RT 并签发新 AT",
            description = "RT 原文、RT 摘要和 CSRF 均保持不变；Redis 中真实 RT、用户索引字段和索引 Key 同步续期。"
                    + "H5 从 HttpOnly Cookie 读取 AT/RT 并重写三个 Cookie；Android 使用 Authorization 提交 AT、"
                    + "在请求体中提交安全存储的 RT。")
    public SessionResponse refresh(
            @RequestBody(required = false) SessionRequest body,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            @RequestHeader(value = CSRF_HEADER, required = false) String csrfToken,
            HttpServletRequest request,
            HttpServletResponse response) {
        AuthClientPlatform platform = AuthClientPlatform.fromHeader(platformHeader);
        String refreshToken = refreshToken(platform, body, request);
        SessionAuthenticationResult result = sessionService.authenticate(
                new SessionAuthenticationCommand(
                        accessToken(platform, request),
                        refreshToken,
                        csrfToken,
                        deviceId));
        if (platform == AuthClientPlatform.H5) {
            // H5 刷新仅通过 Set-Cookie 返回凭据，JSON 响应不携带 AT/RT/CSRF。
            cookieWriter.writeSession(
                    response,
                    result.getAccessToken(),
                    refreshToken,
                    result.getCsrfToken(),
                    result.getRefreshExpiresAt());
        }
        return response(result, platform);
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
            throw new IllegalArgumentException("会话恢复接口仅支持 H5 客户端");
        }
        // bootstrap 只允许 H5：它依赖浏览器携带 SameSite RT Cookie，并由安全拦截器校验来源。
        String refreshToken = refreshToken(platform, body, request);
        SessionAuthenticationResult result = sessionService.bootstrap(
                new SessionBootstrapCommand(
                        accessToken(platform, request), refreshToken, deviceId));
        cookieWriter.writeSession(
                response,
                result.getAccessToken(),
                refreshToken,
                result.getCsrfToken(),
                result.getRefreshExpiresAt());
        return response(result, platform);
    }

    @PostMapping("/logout")
    @Operation(
            summary = "退出当前设备",
            description = "撤销当前固定 RT，并从用户 RT 反向索引删除当前字段；H5 同时清除 AT、RT、XSRF 三个 Cookie。")
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
        if (platform == AuthClientPlatform.H5) {
            cookieWriter.clearSession(response);
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
            description = "使用已校验的 Access Token 确定当前用户，不接受请求体中的 userId；"
                    + "随后批量撤销该用户的全部 Refresh Session。H5 成功后清理浏览器认证 Cookie。")
    public LogoutResponse logoutAll(
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        SessionPrincipal principal = currentPrincipal(request);
        sessionService.logoutAllForUser(principal.userId());
        if (AuthClientPlatform.fromHeader(platformHeader) == AuthClientPlatform.H5) {
            cookieWriter.clearSession(response);
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

    private static String accessToken(
            AuthClientPlatform platform, HttpServletRequest request) {
        if (platform == AuthClientPlatform.ANDROID) {
            // Android 只使用标准 Bearer Header，缺失或格式不符由会话服务返回受控错误。
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            return authorization != null && authorization.startsWith("Bearer ")
                    ? authorization.substring(7) : null;
        }
        // H5 普通/刷新会话仅使用 HttpOnly access_token Cookie，不读取 Authorization 作为降级来源。
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
                AccessTokenAuthenticationInterceptor.PRINCIPAL_ATTRIBUTE);
        if (principal instanceof SessionPrincipal sessionPrincipal) {
            return sessionPrincipal;
        }
        throw new SessionAuthenticationException(
                SessionAuthenticationErrorCode.ACCESS_TOKEN_REQUIRED,
                "Access token is required.",
                true);
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
