package com.example.temperate.web.admin.controller;

import com.example.temperate.common.net.ip.IpAddressIdentity;
import com.example.temperate.common.validation.phone.PhoneNumberInputPolicy;
import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.config.AdminConfigurationService;
import com.example.temperate.service.admin.config.AdminConfigurationSnapshot;
import com.example.temperate.service.admin.config.AdminConfigurationState;
import com.example.temperate.service.admin.config.properties.AdminProperties;
import com.example.temperate.service.admin.login.AdminLoginAccess;
import com.example.temperate.service.admin.login.AdminLoginCompleteCommand;
import com.example.temperate.service.admin.login.AdminLoginService;
import com.example.temperate.service.admin.login.AdminLoginStartResult;
import com.example.temperate.service.admin.registration.AdminRegistrationCompleteCommand;
import com.example.temperate.service.admin.registration.AdminRegistrationService;
import com.example.temperate.service.admin.session.AdminSessionIssue;
import com.example.temperate.service.admin.session.AdminSessionProfile;
import com.example.temperate.service.admin.session.AdminSessionService;
import com.example.temperate.service.registration.component.token.RegistrationTokenGenerator;
import com.example.temperate.service.registration.dto.command.RegistrationSendCodeCommand;
import com.example.temperate.service.registration.dto.command.RegistrationStartCommand;
import com.example.temperate.service.registration.dto.command.RegistrationVerifyCodesCommand;
import com.example.temperate.service.registration.dto.query.RegistrationStatusQuery;
import com.example.temperate.service.registration.dto.result.RegistrationStartResult;
import com.example.temperate.service.registration.dto.result.RegistrationStatusResult;
import com.example.temperate.service.registration.dto.result.VerificationDispatchResult;
import com.example.temperate.service.registration.enums.RegistrationStatus;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.registration.flow.security.RegistrationAccess;
import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.observability.NetworkRiskDiagnosticContext;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthIssue;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.web.admin.security.AdminClientPlatformResolver;
import com.example.temperate.web.admin.security.AdminH5CsrfCookieScopeValidator;
import com.example.temperate.web.admin.security.AdminSessionAuthenticationInterceptor;
import com.example.temperate.web.admin.transport.AdminCookieWriter;
import com.example.temperate.web.auth.diagnostic.filter.AuthRequestTraceFilter;
import com.example.temperate.web.auth.phonecountry.component.TrustedClientIpResolver;
import com.example.temperate.web.auth.session.transport.AuthClientPlatform;
import com.example.temperate.web.risk.NetworkRiskInterceptor;
import com.example.temperate.web.risk.PreAuthTransport;
import com.example.temperate.web.risk.RiskRequestContextResolver;
import com.example.temperate.web.risk.webrtc.WebRtcVerificationTransport;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 暴露单管理员状态、首次注册、hCaptcha 登录、会话恢复和两种退出接口。
 *
 * <p>H5 原始流程与会话 Token 只经安全 Cookie 传输，Android 使用显式 Header/响应字段；Controller
 * 不访问 PostgreSQL，也不向响应返回密码哈希、Secret、验证码或服务端文件路径。</p>
 */
@RestController
@RequestMapping("/api/admin")
@Tag(
        name = "管理员-单管理员认证",
        description = "面向 admin.niko000o.site 和受控 Android 客户端的单管理员初始化、hCaptcha 登录、会话恢复及退出接口；不提供密码重置或第二管理员注册。")
public final class AdminAuthController {

    public static final String DEVICE_HEADER = "X-Device-Installation-Id";
    public static final String PLATFORM_HEADER = "X-Client-Platform";
    public static final String FLOW_TOKEN_HEADER = "X-Admin-Register-Token";
    public static final String LOGIN_FLOW_TOKEN_HEADER = "X-Admin-Login-Flow-Token";
    public static final String FLOW_CSRF_HEADER = "X-Admin-CSRF-Token";
    public static final String CHALLENGE_HEADER = "X-Admin-Challenge";

    private final AdminConfigurationService configurationService;
    private final AdminRegistrationService registrationService;
    private final AdminLoginService loginService;
    private final AdminSessionService sessionService;
    private final AdminCookieWriter cookieWriter;
    private final RegistrationTokenGenerator tokenGenerator;
    private final TrustedClientIpResolver clientIpResolver;
    private final AdminProperties properties;
    private final AdminClientPlatformResolver platformResolver;
    private final AdminH5CsrfCookieScopeValidator csrfCookieScopeValidator;
    private final PreAuthService preAuthService;
    private final PreAuthTransport preAuthTransport;
    private final RiskRequestContextResolver riskContextResolver;
    private final NetworkRiskProperties networkRiskProperties;
    private final WebRtcVerificationTransport webRtcTransport;

    public AdminAuthController(
            AdminConfigurationService configurationService,
            AdminRegistrationService registrationService,
            AdminLoginService loginService,
            AdminSessionService sessionService,
            AdminCookieWriter cookieWriter,
            RegistrationTokenGenerator tokenGenerator,
            TrustedClientIpResolver clientIpResolver,
            AdminProperties properties,
            AdminClientPlatformResolver platformResolver,
            AdminH5CsrfCookieScopeValidator csrfCookieScopeValidator,
            PreAuthService preAuthService,
            PreAuthTransport preAuthTransport,
            RiskRequestContextResolver riskContextResolver,
            NetworkRiskProperties networkRiskProperties,
            WebRtcVerificationTransport webRtcTransport) {
        this.configurationService = Objects.requireNonNull(configurationService);
        this.registrationService = Objects.requireNonNull(registrationService);
        this.loginService = Objects.requireNonNull(loginService);
        this.sessionService = Objects.requireNonNull(sessionService);
        this.cookieWriter = Objects.requireNonNull(cookieWriter);
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator);
        this.clientIpResolver = Objects.requireNonNull(clientIpResolver);
        this.properties = Objects.requireNonNull(properties);
        this.platformResolver = Objects.requireNonNull(platformResolver);
        this.csrfCookieScopeValidator = Objects.requireNonNull(csrfCookieScopeValidator);
        this.preAuthService = Objects.requireNonNull(preAuthService);
        this.preAuthTransport = Objects.requireNonNull(preAuthTransport);
        this.riskContextResolver = Objects.requireNonNull(riskContextResolver);
        this.networkRiskProperties = Objects.requireNonNull(networkRiskProperties);
        this.webRtcTransport = Objects.requireNonNull(webRtcTransport);
    }

    @GetMapping("/auth/state")
    @Operation(summary = "重新检查管理员隐藏配置状态")
    public AdminStateResponse state(HttpServletResponse response) {
        noStore(response);
        AdminConfigurationSnapshot snapshot = configurationService.inspect(true);
        return new AdminStateResponse(
                snapshot.state(),
                snapshot.registrationAllowed(),
                snapshot.loginAllowed(),
                snapshot.message(),
                snapshot.checkedAt());
    }

    @GetMapping("/auth/hcaptcha/config")
    @Operation(summary = "获取管理员前端可公开使用的 hCaptcha Site Key")
    public HcaptchaConfigResponse hcaptchaConfig(HttpServletResponse response) {
        noStore(response);
        return new HcaptchaConfigResponse(
                "HCAPTCHA",
                properties.hcaptcha().siteKey());
    }

    @PostMapping("/auth/register/start")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "开始唯一管理员的十分钟首次注册流程")
    public RegistrationStartResponse startRegistration(
            @Valid @RequestBody RegistrationStartRequest body,
            @RequestHeader(DEVICE_HEADER) String device,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        // 先确认浏览器可以读取即将签发的 CSRF Cookie，避免创建客户端永远无法继续使用的 Redis Flow。
        csrfCookieScopeValidator.requireFlowReadable(request);
        RegistrationStartResult result = registrationService.start(new RegistrationStartCommand(
                body.email(),
                body.countryIso2(),
                body.phoneNumber(),
                device,
                canonicalIp(request)));
        AuthClientPlatform platform = platformResolver.resolve(request);
        if (platform == AuthClientPlatform.H5) {
            cookieWriter.writeRegistration(
                    response,
                    result.registerToken(),
                    result.flowCsrf(),
                    result.challengeHandle());
        }
        return new RegistrationStartResponse(
                platform == AuthClientPlatform.ANDROID ? result.registerToken() : null,
                platform == AuthClientPlatform.ANDROID ? result.flowCsrf() : null,
                result.challengeHandle(),
                properties.hcaptcha().siteKey(),
                result.expiresAt());
    }

    @GetMapping("/auth/register/status")
    @Operation(summary = "恢复管理员首次注册 Flow 状态")
    public RegistrationStatusResponse registrationStatus(
            @RequestHeader(value = FLOW_TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = CHALLENGE_HEADER, required = false) String challenge,
            @RequestHeader(DEVICE_HEADER) String device,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        RegistrationAccess access = registrationAccess(
                token, challenge, device, request, false);
        return registrationStatus(registrationService.status(
                new RegistrationStatusQuery(access)));
    }

    @PostMapping("/auth/register/hcaptcha")
    @Operation(summary = "由后端 WebClient 校验管理员注册 hCaptcha")
    public Mono<RegistrationStatusResponse> verifyRegistrationHcaptcha(
            @Valid @RequestBody HcaptchaRequest body,
            @RequestHeader(value = FLOW_TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = CHALLENGE_HEADER, required = false) String challenge,
            @RequestHeader(DEVICE_HEADER) String device,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        RegistrationAccess access = registrationAccess(
                token, challenge, device, request, true);
        return registrationService.verifyHcaptcha(access, body.hcaptchaToken())
                .map(AdminAuthController::registrationStatus);
    }

    @PostMapping("/auth/register/codes/email/send")
    @Operation(summary = "发送管理员首次注册邮箱验证码")
    public AcceptedResponse sendRegistrationEmailCode(
            @RequestHeader(value = FLOW_TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = CHALLENGE_HEADER, required = false) String challenge,
            @RequestHeader(DEVICE_HEADER) String device,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        VerificationDispatchResult result = registrationService.sendCode(
                new RegistrationSendCodeCommand(
                        registrationAccess(
                                token, challenge, device, request, true),
                        VerificationChannel.EMAIL,
                        VerificationDeliveryMethod.EMAIL));
        return new AcceptedResponse(true, result.acceptedAt());
    }

    @PostMapping("/auth/register/codes/phone/send")
    @Operation(summary = "按国家规则使用 SMS 或 WhatsApp 发送管理员手机验证码")
    public AcceptedResponse sendRegistrationPhoneCode(
            @Valid @RequestBody PhoneCodeRequest body,
            @RequestHeader(value = FLOW_TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = CHALLENGE_HEADER, required = false) String challenge,
            @RequestHeader(DEVICE_HEADER) String device,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        VerificationDispatchResult result = registrationService.sendCode(
                new RegistrationSendCodeCommand(
                        registrationAccess(
                                token, challenge, device, request, true),
                        VerificationChannel.SMS,
                        body.deliveryMethod()));
        return new AcceptedResponse(true, result.acceptedAt());
    }

    @PostMapping("/auth/register/codes/verify")
    @Operation(summary = "原子校验管理员邮箱和手机验证码")
    public RegistrationStatusResponse verifyRegistrationCodes(
            @Valid @RequestBody VerifyCodesRequest body,
            @RequestHeader(value = FLOW_TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = CHALLENGE_HEADER, required = false) String challenge,
            @RequestHeader(DEVICE_HEADER) String device,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        return registrationStatus(registrationService.verifyCodes(
                new RegistrationVerifyCodesCommand(
                        registrationAccess(
                                token, challenge, device, request, true),
                        body.emailCode(),
                        body.phoneCode())));
    }

    @PostMapping("/auth/register/complete")
    @Operation(summary = "原子创建隐藏管理员配置并永久关闭首次注册入口")
    public RegistrationCompleteResponse completeRegistration(
            @Valid @RequestBody CompleteRegistrationRequest body,
            @RequestHeader(value = FLOW_TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = CHALLENGE_HEADER, required = false) String challenge,
            @RequestHeader(DEVICE_HEADER) String device,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        registrationService.complete(new AdminRegistrationCompleteCommand(
                registrationAccess(
                        token, challenge, device, request, true),
                body.password(),
                body.passwordConfirmation()));
        if (platformResolver.resolve(request) == AuthClientPlatform.H5) {
            cookieWriter.clearRegistration(response);
        }
        return new RegistrationCompleteResponse(true, "LOGIN");
    }

    @PostMapping("/auth/login/start")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "创建管理员登录 hCaptcha 的十分钟一次性 Flow")
    public LoginStartResponse startLogin(
            @RequestHeader(DEVICE_HEADER) String device,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        // 登录 Flow 同样必须在创建前确认 Cookie 作用域，避免用户完成 hCaptcha 后才发现部署配置错误。
        csrfCookieScopeValidator.requireFlowReadable(request);
        AdminLoginStartResult result = loginService.start(device, canonicalIp(request));
        AuthClientPlatform platform = platformResolver.resolve(request);
        if (platform == AuthClientPlatform.H5) {
            cookieWriter.writeLogin(
                    response,
                    result.flowToken(),
                    result.flowCsrf(),
                    result.challengeId());
        }
        return new LoginStartResponse(
                platform == AuthClientPlatform.ANDROID ? result.flowToken() : null,
                platform == AuthClientPlatform.ANDROID ? result.flowCsrf() : null,
                result.challengeId(),
                properties.hcaptcha().siteKey(),
                result.expiresAt());
    }

    @PostMapping("/auth/login/complete")
    @Operation(summary = "校验 hCaptcha 及邮箱、国际手机号和密码并签发管理员会话")
    public Mono<LoginResponse> completeLogin(
            @Valid @RequestBody LoginRequest body,
            @RequestHeader(value = LOGIN_FLOW_TOKEN_HEADER, required = false) String flowToken,
            @RequestHeader(value = CHALLENGE_HEADER, required = false) String challenge,
            @RequestHeader(DEVICE_HEADER) String device,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        AuthClientPlatform platform = platformResolver.resolve(request);
        AdminLoginAccess access = loginAccess(
                flowToken, challenge, device, request);
        Object traceAttribute = request.getAttribute(AuthRequestTraceFilter.TRACE_ATTRIBUTE);
        Object invocationAttribute =
                request.getAttribute(NetworkRiskInterceptor.DIAGNOSTIC_INVOCATION_ATTRIBUTE);
        int invocationNo = invocationAttribute instanceof Number number
                ? number.intValue()
                : 0;
        // 登录完成回调可能切换到 Reactor 线程；这里只复制非敏感关联字段，禁止传播整个 Servlet 请求或凭据。
        NetworkRiskDiagnosticContext.Snapshot promotionDiagnostic =
                NetworkRiskDiagnosticContext.snapshot(
                        traceAttribute instanceof String traceId ? traceId : "absent",
                        invocationNo,
                        request.getDispatcherType().name(),
                        "admin_login_promotion");
        return loginService.complete(new AdminLoginCompleteCommand(
                        access,
                        body.email(),
                        body.countryIso2(),
                        body.phoneNumber(),
                        body.password(),
                        body.hcaptchaToken()))
                .map(issue -> NetworkRiskDiagnosticContext.call(
                        promotionDiagnostic,
                        () -> loginResponse(
                                issue,
                                platform,
                                request,
                                response)));
    }

    @PostMapping("/auth/session/bootstrap")
    @Operation(summary = "恢复并滑动续期当前管理员会话")
    public ProfileResponse bootstrap(
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        return profile(currentProfile(request));
    }

    @GetMapping("/me")
    @Operation(summary = "获取当前管理员公开资料")
    public ProfileResponse me(
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        return profile(currentProfile(request));
    }

    @PostMapping("/auth/logout")
    @Operation(summary = "退出当前管理员设备")
    public LogoutResponse logout(
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        sessionService.logout(currentRawToken(request));
        preAuthService.revoke(
                RiskScope.ADMIN,
                preAuthTransport.read(request, RiskScope.ADMIN));
        cookieWriter.clearSession(response);
        preAuthTransport.clearCookie(response, RiskScope.ADMIN);
        return new LogoutResponse(true, "CURRENT");
    }

    @PostMapping("/auth/logout-all")
    @Operation(summary = "退出管理员全部设备")
    public LogoutResponse logoutAll(
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        sessionService.logoutAll();
        preAuthService.revoke(
                RiskScope.ADMIN,
                preAuthTransport.read(request, RiskScope.ADMIN));
        cookieWriter.clearSession(response);
        preAuthTransport.clearCookie(response, RiskScope.ADMIN);
        return new LogoutResponse(true, "ALL");
    }

    private LoginResponse loginResponse(
            AdminSessionIssue issue,
            AuthClientPlatform platform,
            HttpServletRequest request,
            HttpServletResponse response) {
        PreAuthIssue preAuthIssue = promotePreAuth(
                issue,
                request,
                response,
                platform);
        if (platform == AuthClientPlatform.H5) {
            cookieWriter.writeSession(
                    response,
                    issue.rawToken(),
                    tokenGenerator.newFlowCsrf());
            cookieWriter.clearLogin(response);
        }
        return new LoginResponse(
                profile(issue.profile()),
                platform == AuthClientPlatform.ANDROID ? issue.rawToken() : null,
                platform == AuthClientPlatform.ANDROID && preAuthIssue != null
                        ? preAuthIssue.rawToken()
                        : null,
                issue.profile().expiresAt());
    }

    private PreAuthIssue promotePreAuth(
            AdminSessionIssue sessionIssue,
            HttpServletRequest request,
            HttpServletResponse response,
            AuthClientPlatform platform) {
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
            throw new AdminException(
                    AdminErrorCode.ADMIN_PREAUTH_REQUIRED,
                    "Administrator PreAuth is required after login.");
        }
        PreAuthIssue preAuthIssue = preAuthService.promoteAuthenticated(
                access,
                RiskSessionType.ADMIN_SESSION,
                sessionIssue.rawToken(),
                observation.observedAt());
        webRtcTransport.write(response, preAuthIssue);
        if (platform == AuthClientPlatform.H5) {
            preAuthTransport.writeCookie(
                    response,
                    RiskScope.ADMIN,
                    preAuthIssue.rawToken());
        }
        return preAuthIssue;
    }

    private static PreAuthAccess verifiedPreAuthAccess(HttpServletRequest request) {
        Object value = request.getAttribute(NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE);
        return value instanceof PreAuthAccess access ? access : null;
    }

    private RegistrationAccess registrationAccess(
            String headerToken,
            String headerChallenge,
            String device,
            HttpServletRequest request,
            boolean mutation) {
        if (platformResolver.resolve(request) == AuthClientPlatform.H5) {
            // 配置错误必须先于凭据比较暴露，不能伪装成 ADMIN_FLOW_INVALID 或进入 Siteverify。
            csrfCookieScopeValidator.requireFlowReadable(request);
            AdminCookieWriter.RegistrationCookies cookies = cookieWriter.registration(request);
            if (mutation) {
                requireFlowCsrf(cookies.csrf(), request.getHeader(FLOW_CSRF_HEADER));
            }
            return new RegistrationAccess(
                    cookies.token(),
                    cookies.csrf(),
                    cookies.challenge(),
                    device,
                    canonicalIp(request));
        }
        return new RegistrationAccess(
                headerToken,
                request.getHeader(FLOW_CSRF_HEADER),
                headerChallenge,
                device,
                canonicalIp(request));
    }

    private AdminLoginAccess loginAccess(
            String headerToken,
            String headerChallenge,
            String device,
            HttpServletRequest request) {
        if (platformResolver.resolve(request) == AuthClientPlatform.H5) {
            // 登录完成携带一次性 hCaptcha Token，必须先拒绝不可读的 Cookie 作用域以避免无效消费。
            csrfCookieScopeValidator.requireFlowReadable(request);
            AdminCookieWriter.LoginCookies cookies = cookieWriter.login(request);
            requireFlowCsrf(cookies.csrf(), request.getHeader(FLOW_CSRF_HEADER));
            return new AdminLoginAccess(
                    cookies.token(),
                    cookies.csrf(),
                    cookies.challenge(),
                    device,
                    canonicalIp(request));
        }
        return new AdminLoginAccess(
                headerToken,
                request.getHeader(FLOW_CSRF_HEADER),
                headerChallenge,
                device,
                canonicalIp(request));
    }

    private String canonicalIp(HttpServletRequest request) {
        return riskContextResolver.resolve(request)
                .map(TrustedNetworkObservation::clientIp)
                .orElseGet(() -> clientIpResolver.resolve(request)
                        .orElseGet(() -> IpAddressIdentity.parse(
                                        request.getRemoteAddr())
                                .canonicalText()));
    }

    private static void requireFlowCsrf(String cookieValue, String headerValue) {
        byte[] left = (cookieValue == null ? "" : cookieValue)
                .getBytes(StandardCharsets.UTF_8);
        byte[] right = (headerValue == null ? "" : headerValue)
                .getBytes(StandardCharsets.UTF_8);
        if (left.length == 0 || !MessageDigest.isEqual(left, right)) {
            throw new AdminException(
                    AdminErrorCode.ADMIN_FLOW_INVALID,
                    "Administrator flow CSRF is invalid.",
                    null,
                    true,
                    false);
        }
    }

    private static AdminSessionProfile currentProfile(HttpServletRequest request) {
        Object profile = request.getAttribute(
                AdminSessionAuthenticationInterceptor.PROFILE_ATTRIBUTE);
        if (profile instanceof AdminSessionProfile value) {
            return value;
        }
        throw new AdminException(
                AdminErrorCode.ADMIN_SESSION_INVALID,
                "Administrator session is invalid.",
                null,
                false,
                true);
    }

    private static String currentRawToken(HttpServletRequest request) {
        Object token = request.getAttribute(
                AdminSessionAuthenticationInterceptor.RAW_TOKEN_ATTRIBUTE);
        if (token instanceof String value && !value.isBlank()) {
            return value;
        }
        throw new AdminException(
                AdminErrorCode.ADMIN_SESSION_INVALID,
                "Administrator session is invalid.",
                null,
                false,
                true);
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(
                HttpHeaders.CACHE_CONTROL,
                CacheControl.noStore().cachePrivate().getHeaderValue());
    }

    private static RegistrationStatusResponse registrationStatus(
            RegistrationStatusResult result) {
        return new RegistrationStatusResponse(
                result.status(),
                result.humanVerified(),
                result.emailVerified(),
                result.phoneVerified(),
                result.humanVerified() ? maskEmail(result.email()) : null,
                result.humanVerified() ? maskPhone(result.phoneE164()) : null,
                result.expiresAt());
    }

    private static String maskEmail(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        return at <= 0 ? "***" : email.substring(0, 1) + "***" + email.substring(at);
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) {
            return "***";
        }
        return phone.substring(0, Math.min(3, phone.length() - 4))
                + "******"
                + phone.substring(phone.length() - 4);
    }

    private static ProfileResponse profile(AdminSessionProfile profile) {
        return new ProfileResponse(
                profile.email(),
                profile.countryIso2(),
                profile.phoneE164(),
                profile.expiresAt());
    }

    public record AdminStateResponse(
            AdminConfigurationState state,
            boolean registrationAllowed,
            boolean loginAllowed,
            String message,
            Instant checkedAt) {
    }

    public record HcaptchaConfigResponse(String provider, String siteKey) {
    }

    public record RegistrationStartRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$") String countryIso2,
            @NotBlank @Pattern(regexp = PhoneNumberInputPolicy.BASIC_PHONE_PATTERN)
                    String phoneNumber) {

        @Override
        public String toString() {
            return "RegistrationStartRequest[redacted]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RegistrationStartResponse(
            String registerToken,
            String flowCsrf,
            String challengeId,
            String siteKey,
            Instant expiresAt) {

        @Override
        public String toString() {
            return "RegistrationStartResponse[redacted]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RegistrationStatusResponse(
            RegistrationStatus status,
            boolean humanVerified,
            boolean emailVerified,
            boolean phoneVerified,
            String emailMasked,
            String phoneMasked,
            Instant expiresAt) {

        @Override
        public String toString() {
            return "RegistrationStatusResponse[redacted]";
        }
    }

    public record HcaptchaRequest(
            @NotBlank @Size(max = 4096)
                    @Schema(
                            format = "password",
                            example = "<hcaptcha-one-time-token>",
                            accessMode = Schema.AccessMode.WRITE_ONLY)
                    String hcaptchaToken) {

        @Override
        public String toString() {
            return "HcaptchaRequest[redacted]";
        }
    }

    public record PhoneCodeRequest(
            @NotNull VerificationDeliveryMethod deliveryMethod) {
    }

    public record VerifyCodesRequest(
            @NotBlank @Pattern(regexp = "^[0-9]{6}$") String emailCode,
            @NotBlank @Pattern(regexp = "^[0-9]{6}$") String phoneCode) {

        @Override
        public String toString() {
            return "VerifyCodesRequest[redacted]";
        }
    }

    public record CompleteRegistrationRequest(
            @NotBlank @Size(max = 256)
            @Schema(format = "password", example = "********",
                    accessMode = Schema.AccessMode.WRITE_ONLY)
                    String password,
            @NotBlank @Size(max = 256)
            @Schema(format = "password", example = "********",
                    accessMode = Schema.AccessMode.WRITE_ONLY)
                    String passwordConfirmation) {

        @Override
        public String toString() {
            return "CompleteRegistrationRequest[redacted]";
        }
    }

    public record RegistrationCompleteResponse(
            boolean initialized,
            String nextAction) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LoginStartResponse(
            String loginFlowToken,
            String flowCsrf,
            String challengeId,
            String siteKey,
            Instant expiresAt) {

        @Override
        public String toString() {
            return "LoginStartResponse[redacted]";
        }
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$") String countryIso2,
            @NotBlank @Pattern(regexp = PhoneNumberInputPolicy.BASIC_PHONE_PATTERN)
                    String phoneNumber,
            @NotBlank @Size(max = 256)
            @Schema(format = "password", example = "********",
                    accessMode = Schema.AccessMode.WRITE_ONLY)
                    String password,
            @NotBlank @Size(max = 4096)
                    @Schema(format = "password", example = "<hcaptcha-one-time-token>",
                            accessMode = Schema.AccessMode.WRITE_ONLY)
                    String hcaptchaToken) {

        @Override
        public String toString() {
            return "LoginRequest[redacted]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LoginResponse(
            ProfileResponse admin,
            String adminToken,
            String preAuthToken,
            Instant expiresAt) {

        @Override
        public String toString() {
            return "LoginResponse[redacted]";
        }
    }

    public record ProfileResponse(
            String email,
            String countryIso2,
            String phoneE164,
            Instant expiresAt) {

        @Override
        public String toString() {
            return "ProfileResponse[redacted]";
        }
    }

    public record AcceptedResponse(boolean accepted, Instant acceptedAt) {
    }

    public record LogoutResponse(boolean loggedOut, String scope) {
    }
}
