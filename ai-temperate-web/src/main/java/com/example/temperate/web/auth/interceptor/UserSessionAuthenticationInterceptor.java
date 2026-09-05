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
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentLoadtestAccessService;
import com.example.temperate.web.auth.diagnostic.filter.AuthRequestTiming;
import com.example.temperate.web.auth.diagnostic.filter.AuthRequestTraceFilter;
import com.example.temperate.web.auth.session.transport.AuthClientPlatform;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import com.example.temperate.web.risk.NetworkRiskInterceptor;
import com.example.temperate.web.user.membership.payment.loadtest.MembershipPaymentLoadtestRequestPolicy;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    public static final String ACCESS_CREDENTIAL_PRESENT_ATTRIBUTE =
            UserSessionAuthenticationInterceptor.class.getName() + ".accessCredentialPresent";
    public static final String REFRESH_CREDENTIAL_PRESENT_ATTRIBUTE =
            UserSessionAuthenticationInterceptor.class.getName() + ".refreshCredentialPresent";
    public static final String CSRF_PRESENT_ATTRIBUTE =
            UserSessionAuthenticationInterceptor.class.getName() + ".csrfPresent";
    public static final String PREAUTH_ACCESS_PRESENT_ATTRIBUTE =
            UserSessionAuthenticationInterceptor.class.getName() + ".preAuthAccessPresent";
    public static final String BINDING_ATTEMPTED_ATTRIBUTE =
            UserSessionAuthenticationInterceptor.class.getName() + ".bindingAttempted";
    public static final String BINDING_RESULT_ATTRIBUTE =
            UserSessionAuthenticationInterceptor.class.getName() + ".bindingResult";
    private static final String COMPLETED_ATTRIBUTE =
            UserSessionAuthenticationInterceptor.class.getName() + ".completed";
    private static final String PLATFORM_HEADER = "X-Client-Platform";
    private static final String DEVICE_HEADER = "X-Device-Installation-Id";
    private static final String CSRF_HEADER = "X-CSRF-Token";
    private static final String MEMBERSHIP_PLAN_OFFERS_PATH =
            "/api/user/membership-plan-offers";
    private static final Logger LOGGER =
            LoggerFactory.getLogger(UserSessionAuthenticationInterceptor.class);

    private final AccessSessionService accessSessionService;
    private final AuthCookieWriter cookieWriter;
    private final PreAuthService preAuthService;
    private final NetworkRiskProperties networkRiskProperties;
    private final MembershipExpirationService membershipExpirationService;
    private final MembershipPaymentLoadtestRequestPolicy loadtestRequestPolicy;
    private final MembershipPaymentLoadtestAccessService loadtestAccessService;

    @Autowired
    public UserSessionAuthenticationInterceptor(
            AccessSessionService accessSessionService,
            AuthCookieWriter cookieWriter,
            PreAuthService preAuthService,
            NetworkRiskProperties networkRiskProperties,
            MembershipExpirationService membershipExpirationService,
            MembershipPaymentLoadtestRequestPolicy loadtestRequestPolicy,
            MembershipPaymentLoadtestAccessService loadtestAccessService) {
        this.accessSessionService = Objects.requireNonNull(accessSessionService);
        this.cookieWriter = Objects.requireNonNull(cookieWriter);
        this.preAuthService = Objects.requireNonNull(preAuthService);
        this.networkRiskProperties = Objects.requireNonNull(networkRiskProperties);
        this.membershipExpirationService =
                Objects.requireNonNull(membershipExpirationService);
        this.loadtestRequestPolicy = Objects.requireNonNull(loadtestRequestPolicy);
        this.loadtestAccessService = Objects.requireNonNull(loadtestAccessService);
    }

    public UserSessionAuthenticationInterceptor(
            AccessSessionService accessSessionService,
            AuthCookieWriter cookieWriter,
            PreAuthService preAuthService,
            NetworkRiskProperties networkRiskProperties,
            MembershipExpirationService membershipExpirationService) {
        this(
                accessSessionService,
                cookieWriter,
                preAuthService,
                networkRiskProperties,
                membershipExpirationService,
                MembershipPaymentLoadtestRequestPolicy.disabled(),
                rawAccessToken -> {
                    throw new IllegalStateException("Loadtest authentication is disabled.");
                });
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        AuthRequestTiming.begin(request, AuthRequestTiming.Stage.SESSION);
        try {
            return authenticate(request, response);
        } catch (SessionAuthenticationException exception) {
            AuthRequestTiming.recordErrorCode(request, exception.code().name());
            throw exception;
        } finally {
            long durationMillis = AuthRequestTiming.complete(
                    request, AuthRequestTiming.Stage.SESSION);
            if (request.getAttribute(AuthRequestTraceFilter.TRACE_ATTRIBUTE) != null) {
                LOGGER.info(
                        "event=user_session_auth_completed traceId={} platform={} authenticated={} "
                                + "errorCode={} durationMs={} accessCredentialPresent={} "
                                + "refreshCredentialPresent={} csrfHeaderPresent={} "
                                + "preAuthAccessPresent={} bindingAttempted={} bindingResult={}",
                        traceId(request),
                        safePlatform(request.getHeader(PLATFORM_HEADER)),
                        request.getAttribute(PRINCIPAL_ATTRIBUTE) instanceof SessionPrincipal,
                        AuthRequestTiming.errorCode(request),
                        durationMillis,
                        diagnosticValue(request, ACCESS_CREDENTIAL_PRESENT_ATTRIBUTE),
                        diagnosticValue(request, REFRESH_CREDENTIAL_PRESENT_ATTRIBUTE),
                        diagnosticValue(request, CSRF_PRESENT_ATTRIBUTE),
                        diagnosticValue(request, PREAUTH_ACCESS_PRESENT_ATTRIBUTE),
                        diagnosticValue(request, BINDING_ATTEMPTED_ATTRIBUTE),
                        diagnosticValue(request, BINDING_RESULT_ATTRIBUTE));
            }
        }
    }

    private boolean authenticate(
            HttpServletRequest request,
            HttpServletResponse response) {
        Object existingPrincipal = request.getAttribute(PRINCIPAL_ATTRIBUTE);
        if (Boolean.TRUE.equals(request.getAttribute(COMPLETED_ATTRIBUTE))
                && existingPrincipal instanceof SessionPrincipal principal) {
            establishSecurityContext(request, principal);
            return true;
        }

        if (loadtestRequestPolicy.matchesTokenMint(request)) {
            // Token 签发入口由本机回环 Controller 自己做地址和开关校验，不应被当成带 Bearer 的业务请求。
            return true;
        }
        if (loadtestRequestPolicy.matches(request)) {
            // 只有精确压测路由可以跳过 RT、Device 与 CSRF；原始 AT 验证后立即丢弃，不写 Cookie 或响应头。
            SessionPrincipal principal = loadtestAccessService.authenticate(bearerToken(request));
            expireMembership(principal.userId());
            establishSecurityContext(request, principal);
            request.setAttribute(COMPLETED_ATTRIBUTE, Boolean.TRUE);
            return true;
        }

        AuthClientPlatform platform = AuthClientPlatform.fromHeader(
                request.getHeader(PLATFORM_HEADER));
        boolean explicit = platform.usesExplicitTokenTransport();
        String accessCredential = explicit
                ? bearerToken(request)
                : cookieToken(request, AuthCookieWriter.ACCESS_COOKIE);
        String refreshCredential = explicit
                ? request.getHeader(REFRESH_HEADER)
                : cookieToken(request, AuthCookieWriter.REFRESH_COOKIE);
        String csrfCredential = request.getHeader(CSRF_HEADER);
        request.setAttribute(
                ACCESS_CREDENTIAL_PRESENT_ATTRIBUTE,
                present(accessCredential));
        request.setAttribute(
                REFRESH_CREDENTIAL_PRESENT_ATTRIBUTE,
                present(refreshCredential));
        request.setAttribute(CSRF_PRESENT_ATTRIBUTE, present(csrfCredential));
        request.setAttribute(
                PREAUTH_ACCESS_PRESENT_ATTRIBUTE,
                request.getAttribute(NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE)
                        instanceof PreAuthAccess);
        SessionAccessCommand command = new SessionAccessCommand(
                accessCredential,
                refreshCredential,
                csrfCredential,
                request.getHeader(DEVICE_HEADER));
        PreAuthSessionBinding binding = userPreAuthBinding(request, command.refreshToken());
        SessionAccessResult result = binding == null
                ? accessSessionService.authenticateOrRenew(command)
                : accessSessionService.authenticateOrRenew(command, binding);

        // 报价接口必须保持只读；其转换策略会在内存中把已过期付费套餐按 FREE 计算，不触发惰性写库。
        if (!isMembershipPlanOfferRead(request)) {
            expireMembership(result.principal().userId());
        }

        establishSecurityContext(request, result.principal());
        if (result.renewed()) {
            // 续签只改变 AT；原 RT 和 CSRF 均保持不变，业务响应体也不做包装。
            if (explicit) {
                response.setHeader(NEW_ACCESS_HEADER, result.renewedAccessToken());
            } else {
                cookieWriter.writeAccessToken(response, result.renewedAccessToken());
            }
            // renewed 必须最后写入，避免客户端看到成功标记时平台专属的新 AT 尚未写完。
            response.setHeader(RENEWED_HEADER, "true");
        }
        // 请求属性只保存完成标记和 Principal，禁止让新 AT 跟随异步/SSE 请求生命周期驻留。
        request.setAttribute(COMPLETED_ATTRIBUTE, Boolean.TRUE);
        return true;
    }

    private void expireMembership(long userId) {
        try {
            // 只有认证分支已经裁决出的内部用户 ID 才能触发会员降级，禁止使用请求参数选择待更新账号。
            membershipExpirationService.expireIfDue(userId);
        } catch (RuntimeException exception) {
            throw new SessionAuthenticationException(
                    SessionAuthenticationErrorCode.INFRASTRUCTURE_UNAVAILABLE,
                    "Membership validation is temporarily unavailable.",
                    false,
                    exception);
        }
    }

    private static boolean isMembershipPlanOfferRead(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String expected = (contextPath == null ? "" : contextPath)
                + MEMBERSHIP_PLAN_OFFERS_PATH;
        return "GET".equals(request.getMethod())
                && expected.equals(request.getRequestURI());
    }

    private PreAuthSessionBinding userPreAuthBinding(
            HttpServletRequest request,
            String rawRefreshToken) {
        // 缺失 RT 必须由统一会话服务稳定映射为 REFRESH_TOKEN_REQUIRED，不能被 PreAuth 提前改写错误语义。
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            request.setAttribute(BINDING_ATTEMPTED_ATTRIBUTE, Boolean.FALSE);
            request.setAttribute(BINDING_RESULT_ATTRIBUTE, "skipped_missing_refresh");
            return null;
        }
        Object value = request.getAttribute(NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE);
        if (!(value instanceof PreAuthAccess access)) {
            request.setAttribute(BINDING_ATTEMPTED_ATTRIBUTE, Boolean.FALSE);
            request.setAttribute(BINDING_RESULT_ATTRIBUTE, "skipped_missing_preauth");
            return null;
        }
        request.setAttribute(BINDING_ATTEMPTED_ATTRIBUTE, Boolean.TRUE);
        AuthRequestTiming.begin(request, AuthRequestTiming.Stage.PREAUTH_BINDING);
        try {
            PreAuthSessionBinding binding = preAuthService.requireSessionBinding(
                    access,
                    RiskScope.USER,
                    RiskSessionType.USER_REFRESH,
                    rawRefreshToken);
            request.setAttribute(BINDING_RESULT_ATTRIBUTE, "succeeded");
            return binding;
        } catch (IllegalArgumentException exception) {
            if (networkRiskProperties.mode() == NetworkRiskMode.OBSERVE) {
                request.setAttribute(BINDING_RESULT_ATTRIBUTE, "observe_fallback");
                return null;
            }
            request.setAttribute(BINDING_RESULT_ATTRIBUTE, "preauth_required");
            // 已登录会话绑定的 PreAuth 凭据失效或解绑属于不可恢复的认证失败，必须返回 401 并彻底清理会话 Cookie。
            throw new SessionAuthenticationException(
                    SessionAuthenticationErrorCode.PREAUTH_REQUIRED,
                    "Authenticated PreAuth is no longer bound to this session.",
                    true,
                    exception);
        } finally {
            AuthRequestTiming.complete(request, AuthRequestTiming.Stage.PREAUTH_BINDING);
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(AuthRequestTraceFilter.TRACE_ATTRIBUTE);
        return value instanceof String trace ? trace : "absent";
    }

    private static String diagnosticValue(
            HttpServletRequest request,
            String attributeName) {
        Object value = request.getAttribute(attributeName);
        return value == null ? "unavailable" : String.valueOf(value);
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

    private static String safePlatform(String platformHeader) {
        try {
            return AuthClientPlatform.fromHeader(platformHeader).name();
        } catch (IllegalArgumentException exception) {
            return "UNSUPPORTED";
        }
    }
}
