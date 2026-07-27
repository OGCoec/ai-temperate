package com.example.temperate.web.admin.security;

import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.config.properties.AdminProperties;
import com.example.temperate.service.admin.session.AdminSessionProfile;
import com.example.temperate.service.admin.session.AdminSessionService;
import com.example.temperate.service.registration.component.token.RegistrationTokenGenerator;
import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthSessionBinding;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.web.admin.transport.AdminCookieWriter;
import com.example.temperate.web.auth.session.transport.AuthClientPlatform;
import com.example.temperate.web.risk.NetworkRiskInterceptor;
import com.example.temperate.web.risk.PreAuthTransport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 在网络风险和 WebRTC 校验之后认证管理员 Cookie/Bearer 会话、续期 Redis 状态并建立安全上下文。
 *
 * <p>本拦截器复用上游已经验证的 PreAuth，不重新信任客户端提交的另一份 PreAuth；成功请求结束或任意异常时
 * 都会清理线程级 {@link org.springframework.security.core.context.SecurityContext}。它不读取或保存密码。</p>
 */
@Component
public final class AdminSessionAuthenticationInterceptor implements HandlerInterceptor {

    public static final String RAW_TOKEN_ATTRIBUTE =
            AdminSessionAuthenticationInterceptor.class.getName() + ".rawToken";
    public static final String PROFILE_ATTRIBUTE =
            AdminSessionAuthenticationInterceptor.class.getName() + ".profile";

    private static final String ADMIN_PREFIX = "/api/admin";
    private static final String STATE_PATH = "/api/admin/auth/state";
    private static final String PREAUTH_PATH = "/api/admin/_edge/pre-auth";
    private static final String CHALLENGE_PATH = "/api/admin/_edge/risk-challenge";
    private static final String WEBRTC_START_PATH = "/api/admin/_edge/webrtc/start";
    private static final String WEBRTC_REPORT_PATH = "/api/admin/_edge/webrtc/report";
    private static final String PHONE_COUNTRY_PATH = "/api/admin/auth/phone-country";
    private static final String HCAPTCHA_CONFIG_PATH = "/api/admin/auth/hcaptcha/config";
    private static final String HCAPTCHA_PAGE_PATH = "/api/admin/auth/hcaptcha/page";
    private static final String HCAPTCHA_PAGE_STYLE_PATH =
            "/api/admin/auth/hcaptcha/page.css";
    private static final String HCAPTCHA_PAGE_SCRIPT_PATH =
            "/api/admin/auth/hcaptcha/page.js";
    private static final String REGISTER_PREFIX = "/api/admin/auth/register";
    private static final String LOGIN_PREFIX = "/api/admin/auth/login";
    private static final String SESSION_BOOTSTRAP_PATH =
            "/api/admin/auth/session/bootstrap";
    private static final String DEVICE_HEADER = "X-Device-Installation-Id";

    private final AdminSessionService sessionService;
    private final AdminCookieWriter cookieWriter;
    private final RegistrationTokenGenerator tokenGenerator;
    private final Set<String> allowedOrigins;
    private final AdminClientPlatformResolver platformResolver;
    private final AdminH5CsrfCookieScopeValidator csrfCookieScopeValidator;
    private final NetworkRiskProperties networkRiskProperties;
    private final PreAuthService preAuthService;
    private final PreAuthTransport preAuthTransport;

    public AdminSessionAuthenticationInterceptor(
            AdminSessionService sessionService,
            AdminCookieWriter cookieWriter,
            RegistrationTokenGenerator tokenGenerator,
            AdminProperties properties,
            AdminClientPlatformResolver platformResolver,
            AdminH5CsrfCookieScopeValidator csrfCookieScopeValidator,
            NetworkRiskProperties networkRiskProperties,
            PreAuthService preAuthService,
            PreAuthTransport preAuthTransport) {
        this.sessionService = Objects.requireNonNull(sessionService);
        this.cookieWriter = Objects.requireNonNull(cookieWriter);
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator);
        this.allowedOrigins = Set.copyOf(
                Objects.requireNonNull(properties).allowedOrigins());
        this.platformResolver = Objects.requireNonNull(platformResolver);
        this.csrfCookieScopeValidator = Objects.requireNonNull(csrfCookieScopeValidator);
        this.networkRiskProperties = Objects.requireNonNull(networkRiskProperties);
        this.preAuthService = Objects.requireNonNull(preAuthService);
        this.preAuthTransport = Objects.requireNonNull(preAuthTransport);
    }

    /**
     * 在进入受保护管理员 Controller 前认证并滑动续期当前会话。
     *
     * <p>会话 Token 的存在性必须先于 PreAuth 会话绑定判断：登录页没有管理员会话属于正常未认证状态，
     * 必须返回 {@code ADMIN_SESSION_INVALID}，不能误报为 PreAuth 失效并触发第二轮 WebRTC 初始化。</p>
     */
    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        if (shouldSkip(request)) {
            return true;
        }
        try {
            AuthClientPlatform platform = platformResolver.resolve(request);
            if (platform == AuthClientPlatform.H5) {
                // 成功访问会重写 CSRF Cookie，因此必须先确认页面可读取管理员会话 Cookie 的作用域。
                csrfCookieScopeValidator.requireSessionReadable(request);
            }
            if (platform == AuthClientPlatform.H5
                    && SESSION_BOOTSTRAP_PATH.equals(path(request))
                    && !allowedOrigins.contains(request.getHeader("Origin"))) {
                throw invalidSession("Administrator bootstrap origin is invalid.", null);
            }

            String rawToken = requiredSessionToken(request, platform);
            String device = request.getHeader(DEVICE_HEADER);
            String rawPreAuthToken = networkRiskProperties.mode() == NetworkRiskMode.DISABLED
                    ? null
                    : preAuthTransport.read(request, RiskScope.ADMIN);
            PreAuthSessionBinding binding = adminPreAuthBinding(request, rawToken);
            AdminSessionProfile profile = binding == null
                    ? sessionService.touch(rawToken, device)
                    : sessionService.touch(rawToken, device, binding);
            if (binding != null) {
                /*
                 * 会话与 PreAuth 的晋升已在同一个 Redis Lua 中提交。提交后只允许用同一摘要的原始 Token
                 * 重载快照，禁止把另一份尚未经过上游风险校验的客户端 PreAuth 写回请求上下文。
                 */
                PreAuthAccess refreshedAccess = preAuthService.resolve(
                                RiskScope.ADMIN,
                                rawPreAuthToken,
                                device)
                        .filter(access -> access.tokenDigest().equals(binding.tokenDigest()))
                        .orElseThrow(() -> new AdminException(
                                AdminErrorCode.ADMIN_PREAUTH_REQUIRED,
                                "Administrator PreAuth could not be reloaded after session renewal.",
                                null,
                                false,
                                false));
                request.setAttribute(
                        NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE,
                        refreshedAccess);
            }

            AdminPrincipal principal = new AdminPrincipal(
                    profile.email(), profile.countryIso2(), profile.phoneE164());
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            request.setAttribute(RAW_TOKEN_ATTRIBUTE, rawToken);
            request.setAttribute(PROFILE_ATTRIBUTE, profile);
            if (platform == AuthClientPlatform.H5) {
                String csrf = cookieWriter.csrfToken(request);
                if (csrf == null || csrf.isBlank()) {
                    csrf = tokenGenerator.newFlowCsrf();
                }
                cookieWriter.refreshSession(response, rawToken, csrf, profile.expiresAt());
            }
            return true;
        } catch (AdminException exception) {
            SecurityContextHolder.clearContext();
            throw exception;
        } catch (RuntimeException exception) {
            SecurityContextHolder.clearContext();
            throw invalidSession("Administrator session is invalid.", exception);
        }
    }

    /**
     * 清理本次请求建立的管理员安全上下文，防止 Servlet 工作线程复用时泄漏到后续请求。
     */
    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception) {
        SecurityContextHolder.clearContext();
    }

    private boolean shouldSkip(HttpServletRequest request) {
        String path = path(request);
        if (!path.startsWith(ADMIN_PREFIX)) {
            return true;
        }
        return STATE_PATH.equals(path)
                || PREAUTH_PATH.equals(path)
                || CHALLENGE_PATH.equals(path)
                || WEBRTC_START_PATH.equals(path)
                || WEBRTC_REPORT_PATH.equals(path)
                || PHONE_COUNTRY_PATH.equals(path)
                || HCAPTCHA_CONFIG_PATH.equals(path)
                || HCAPTCHA_PAGE_PATH.equals(path)
                // 受控页面的公开子资源不包含 Site Key、challenge 或管理员会话数据。
                || HCAPTCHA_PAGE_STYLE_PATH.equals(path)
                || HCAPTCHA_PAGE_SCRIPT_PATH.equals(path)
                || path.startsWith(REGISTER_PREFIX)
                || path.startsWith(LOGIN_PREFIX);
    }

    private String requiredSessionToken(
            HttpServletRequest request,
            AuthClientPlatform platform) {
        if (platform == AuthClientPlatform.ANDROID) {
            return bearerToken(request.getHeader("Authorization"));
        }
        String token = cookieWriter.sessionToken(request);
        if (token == null || token.isBlank()) {
            throw invalidSession("Administrator session is invalid.", null);
        }
        return token;
    }

    private PreAuthSessionBinding adminPreAuthBinding(
            HttpServletRequest request,
            String rawSessionToken) {
        if (networkRiskProperties.mode() == NetworkRiskMode.DISABLED) {
            return null;
        }
        Object verified = request.getAttribute(
                NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE);
        PreAuthAccess access = verified instanceof PreAuthAccess value
                ? value
                : null;
        if (access == null) {
            if (networkRiskProperties.mode() == NetworkRiskMode.OBSERVE) {
                return null;
            }
            throw new AdminException(
                    AdminErrorCode.ADMIN_PREAUTH_REQUIRED,
                    "Administrator PreAuth is required.",
                    null,
                    false,
                    false);
        }
        try {
            return preAuthService.requireSessionBinding(
                    access,
                    RiskScope.ADMIN,
                    RiskSessionType.ADMIN_SESSION,
                    rawSessionToken);
        } catch (IllegalArgumentException exception) {
            if (networkRiskProperties.mode() == NetworkRiskMode.OBSERVE) {
                return null;
            }
            throw new AdminException(
                    AdminErrorCode.ADMIN_PREAUTH_REQUIRED,
                    "Administrator PreAuth is no longer bound to this session.",
                    exception,
                    false,
                    false);
        }
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw invalidSession("Administrator session is invalid.", null);
        }
        String token = authorization.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            throw invalidSession("Administrator session is invalid.", null);
        }
        return token;
    }

    private static AdminException invalidSession(String message, Throwable cause) {
        return new AdminException(
                AdminErrorCode.ADMIN_SESSION_INVALID,
                message,
                cause,
                false,
                true);
    }

    private static String path(HttpServletRequest request) {
        String context = request.getContextPath();
        String uri = request.getRequestURI();
        return context == null || context.isEmpty()
                ? uri
                : uri.substring(context.length());
    }
}
