package com.example.temperate.web.auth.interceptor;

import com.example.temperate.service.auth.session.access.AccessSessionService;
import com.example.temperate.service.auth.session.access.dto.SessionAccessCommand;
import com.example.temperate.service.auth.session.access.dto.SessionAccessResult;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthSessionBinding;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.service.user.membership.MembershipExpirationService;
import com.example.temperate.web.auth.session.transport.AuthClientPlatform;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import com.example.temperate.web.risk.NetworkRiskInterceptor;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 对普通用户受保护请求执行 RT-first 会话认证、付费会员惰性过期，并把同请求 AT 续签结果写入平台专属传输位置。
 *
 * <p>H5 只读取 HttpOnly Cookie，Android 只读取 Header；两端都必须提交设备与 CSRF。请求级结果会在
 * Servlet 异步再次分派时复用，避免 SSE 或异步 Controller 重复访问 Redis、重复续签、重复会员检查或重复写响应头。</p>
 */
@Component
public final class UserSessionAuthenticationInterceptor implements HandlerInterceptor {

    public static final String PRINCIPAL_ATTRIBUTE =
            UserSessionAuthenticationInterceptor.class.getName() + ".principal";
    public static final String RENEWED_HEADER = "X-Session-Renewed";
    public static final String NEW_ACCESS_HEADER = "X-New-Access-Token";
    public static final String REFRESH_HEADER = "X-Refresh-Token";
    private static final String COMPLETED_ATTRIBUTE =
            UserSessionAuthenticationInterceptor.class.getName() + ".completed";
    private static final String PLATFORM_HEADER = "X-Client-Platform";
    private static final String DEVICE_HEADER = "X-Device-Installation-Id";
    private static final String CSRF_HEADER = "X-CSRF-Token";

    private final AccessSessionService accessSessionService;
    private final AuthCookieWriter cookieWriter;
    private final PreAuthService preAuthService;
    private final NetworkRiskProperties networkRiskProperties;
    private final MembershipExpirationService membershipExpirationService;

    public UserSessionAuthenticationInterceptor(
            AccessSessionService accessSessionService,
            AuthCookieWriter cookieWriter,
            PreAuthService preAuthService,
            NetworkRiskProperties networkRiskProperties,
            MembershipExpirationService membershipExpirationService) {
        this.accessSessionService = Objects.requireNonNull(accessSessionService);
        this.cookieWriter = Objects.requireNonNull(cookieWriter);
        this.preAuthService = Objects.requireNonNull(preAuthService);
        this.networkRiskProperties = Objects.requireNonNull(networkRiskProperties);
        this.membershipExpirationService =
                Objects.requireNonNull(membershipExpirationService);
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        Object existingPrincipal = request.getAttribute(PRINCIPAL_ATTRIBUTE);
        if (Boolean.TRUE.equals(request.getAttribute(COMPLETED_ATTRIBUTE))
                && existingPrincipal instanceof SessionPrincipal principal) {
            establishSecurityContext(request, principal);
            return true;
        }

        AuthClientPlatform platform = AuthClientPlatform.fromHeader(
                request.getHeader(PLATFORM_HEADER));
        SessionAccessCommand command = new SessionAccessCommand(
                platform == AuthClientPlatform.ANDROID
                        ? bearerToken(request) : cookieToken(request, AuthCookieWriter.ACCESS_COOKIE),
                platform == AuthClientPlatform.ANDROID
                        ? request.getHeader(REFRESH_HEADER)
                        : cookieToken(request, AuthCookieWriter.REFRESH_COOKIE),
                request.getHeader(CSRF_HEADER),
                request.getHeader(DEVICE_HEADER));
        PreAuthSessionBinding binding = userPreAuthBinding(request, command.refreshToken());
        SessionAccessResult result = binding == null
                ? accessSessionService.authenticateOrRenew(command)
                : accessSessionService.authenticateOrRenew(command, binding);

        try {
            // 只有会话已经认证出的内部用户 ID 才能触发会员降级，禁止使用请求参数选择待更新账号。
            membershipExpirationService.expireIfDue(result.principal().userId());
        } catch (RuntimeException exception) {
            throw new SessionAuthenticationException(
                    SessionAuthenticationErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                    "Membership validation is temporarily unavailable.",
                    false,
                    exception);
        }

        establishSecurityContext(request, result.principal());
        if (result.renewed()) {
            // 续签只改变 AT；原 RT 和 CSRF 均保持不变，业务响应体也不做包装。
            if (platform == AuthClientPlatform.H5) {
                cookieWriter.writeAccessToken(response, result.renewedAccessToken());
            } else {
                response.setHeader(NEW_ACCESS_HEADER, result.renewedAccessToken());
            }
            // renewed 必须最后写入，避免客户端看到成功标记时平台专属的新 AT 尚未写完。
            response.setHeader(RENEWED_HEADER, "true");
        }
        // 请求属性只保存完成标记和 Principal，禁止让新 AT 跟随异步/SSE 请求生命周期驻留。
        request.setAttribute(COMPLETED_ATTRIBUTE, Boolean.TRUE);
        return true;
    }

    private PreAuthSessionBinding userPreAuthBinding(
            HttpServletRequest request,
            String rawRefreshToken) {
        // 缺失 RT 必须由统一会话服务稳定映射为 REFRESH_TOKEN_REQUIRED，不能被 PreAuth 提前改写错误语义。
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return null;
        }
        Object value = request.getAttribute(NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE);
        if (!(value instanceof PreAuthAccess access)) {
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

    private static void establishSecurityContext(
            HttpServletRequest request,
            SessionPrincipal principal) {
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        // 已验证的原始 AT 不再参与下游授权，SecurityContext 只保留主体，避免凭据被无意传播。
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static String bearerToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        return authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : null;
    }

    private static String cookieToken(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
