package com.example.temperate.web.auth.login.controller;

import com.example.temperate.common.net.ip.IpAddressIdentity;
import com.example.temperate.common.validation.phone.PhoneNumberInputPolicy;
import com.example.temperate.service.auth.login.code.dto.LoginCodeAccess;
import com.example.temperate.service.auth.login.code.dto.LoginCodeStartCommand;
import com.example.temperate.service.auth.login.code.dto.LoginCodeStartResult;
import com.example.temperate.service.auth.login.code.service.LoginCodeFlowService;
import com.example.temperate.service.auth.login.dto.result.LoginFlowStatus;
import com.example.temperate.service.auth.login.dto.result.LoginResult;
import com.example.temperate.service.auth.login.strategy.LoginStrategyRegistry;
import com.example.temperate.service.auth.login.strategy.LoginStrategyRequest;
import com.example.temperate.service.auth.login.strategy.LoginStrategyType;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.preauth.domain.PreAuthIssue;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.service.auth.totp.login.TotpLoginService;
import com.example.temperate.web.auth.flow.transport.AuthFlowCookieWriter;
import com.example.temperate.web.auth.session.transport.AuthClientPlatform;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import com.example.temperate.web.risk.PreAuthTransport;
import com.example.temperate.web.risk.NetworkRiskInterceptor;
import com.example.temperate.web.risk.RiskRequestContextResolver;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 密码与验证码登录的 HTTP 接口控制器。
 *
 * <p>用途：接收登录输入、调用登录策略或验证码流程，并按客户端平台写入对应的会话传输结果。</p>
 *
 * <p>传输安全边界：H5 登录成功只通过 HttpOnly Cookie 接收 AT/RT，响应 JSON 省略三类 Token；Android 才从
 * 响应体接收 Token 并由客户端安全存储。控制器不把平台头当作身份凭据，底层服务仍校验登录材料。</p>
 */
@RestController
@RequestMapping("/api/auth/login")
@Tag(
        name = "认证-用户登录",
        description = "提供邮箱或国际手机号加密码登录，以及邮箱验证码、手机验证码登录；"
                + "手机验证码可由服务端按请求选择 SMS 或 WhatsApp 投递。"
                + "验证码流程每次只完成人机验证一次；登录成功后 H5 通过隔离路径的安全 Cookie 接收凭证，"
                + "Android 通过响应体接收凭证并交由 AndroidKeyStore 加密存储，"
                + "不负责注册、找回密码和账号资料维护。")
public final class LoginController {

    private static final String DEVICE_HEADER = "X-Device-Installation-Id";
    private static final String PLATFORM_HEADER = "X-Client-Platform";
    private static final String FLOW_TOKEN_HEADER = "X-Login-Flow-Token";
    private static final String CHALLENGE_HEADER = "X-Turnstile-Challenge";
    private static final String TOTP_FLOW_TOKEN_HEADER = "X-TOTP-Flow-Token";

    private final LoginStrategyRegistry strategyRegistry;
    private final LoginCodeFlowService codeFlowService;
    private final TotpLoginService totpLoginService;
    private final AuthCookieWriter cookieWriter;
    private final AuthFlowCookieWriter flowCookieWriter;
    private final PreAuthService preAuthService;
    private final PreAuthTransport preAuthTransport;
    private final RiskRequestContextResolver riskContextResolver;
    private final NetworkRiskProperties networkRiskProperties;

    public LoginController(
            LoginStrategyRegistry strategyRegistry,
            LoginCodeFlowService codeFlowService,
            TotpLoginService totpLoginService,
            AuthCookieWriter cookieWriter,
            AuthFlowCookieWriter flowCookieWriter,
            PreAuthService preAuthService,
            PreAuthTransport preAuthTransport,
            RiskRequestContextResolver riskContextResolver,
            NetworkRiskProperties networkRiskProperties) {
        this.strategyRegistry = strategyRegistry;
        this.codeFlowService = codeFlowService;
        this.totpLoginService = totpLoginService;
        this.cookieWriter = cookieWriter;
        this.flowCookieWriter = flowCookieWriter;
        this.preAuthService = preAuthService;
        this.preAuthTransport = preAuthTransport;
        this.riskContextResolver = riskContextResolver;
        this.networkRiskProperties = networkRiskProperties;
    }

    @PostMapping("/password")
    @Operation(
            summary = "使用邮箱或国际手机号加密码登录",
            description = "密码登录不经过 Turnstile；邮箱和手机号输入互斥。"
                    + "凭据比对成功后若 SHOPPING_V1 强度或存储的策略元数据不合格，"
                    + "返回 HTTP 409 和 PASSWORD_RESET_REQUIRED，不创建会话。")
    public LoginResponse password(
            @Valid @RequestBody PasswordLoginRequest body,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        AuthClientPlatform platform = AuthClientPlatform.fromHeader(platformHeader);
        LoginResult result = strategyRegistry.login(
                LoginStrategyType.PASSWORD,
                new LoginStrategyRequest(
                        body.email(),
                        body.countryIso2(),
                        body.phoneNumber(),
                        body.password(),
                        null,
                        null,
                        null,
                        deviceId,
                        canonicalIp(request)));
        return transportResult(result, platform, request, response, false);
    }

    @PostMapping("/code/start")
    @Operation(
            summary = "开始验证码登录流程",
            description = "根据 EMAIL_CODE 或 SMS_CODE 创建短期登录流程；外部响应不泄露账号是否存在。")
    public LoginCodeStartResult startCodeLogin(
            @Valid @RequestBody CodeStartRequest body,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            HttpServletRequest request) {
        return codeFlowService.start(new LoginCodeStartCommand(
                body.strategyType(),
                body.email(),
                body.countryIso2(),
                body.phoneNumber(),
                deviceId,
                canonicalIp(request)));
    }

    @PostMapping("/code/turnstile")
    @Operation(
            summary = "验证登录流程的人机挑战",
            description = "服务端校验 Cloudflare Turnstile token、action、hostname 和重放状态；客户端布尔值不作为依据。")
    public Mono<FlowAcceptedResponse> verifyCodeTurnstile(
            @Valid @RequestBody TurnstileRequest body,
            @RequestHeader(FLOW_TOKEN_HEADER) String flowToken,
            @RequestHeader(CHALLENGE_HEADER) String challenge,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            HttpServletRequest request) {
        return codeFlowService.verifyTurnstile(
                        access(flowToken, challenge, deviceId, request),
                        body.turnstileToken())
                .thenReturn(new FlowAcceptedResponse(
                        true, "人机验证已通过，可以手动发送验证码。"));
    }

    @PostMapping("/code/send")
    @Operation(
            summary = "发送登录验证码",
            description = "人机验证通过后按流程渠道发送验证码，并执行设备维度冷却和固定窗口风控。")
    public FlowAcceptedResponse sendCode(
            @Valid @RequestBody(required = false) CodeSendRequest body,
            @RequestHeader(FLOW_TOKEN_HEADER) String flowToken,
            @RequestHeader(CHALLENGE_HEADER) String challenge,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            HttpServletRequest request) {
        codeFlowService.sendCode(
                access(flowToken, challenge, deviceId, request),
                body == null ? null : body.deliveryMethod());
        return new FlowAcceptedResponse(true, "如果账号存在，验证码已经发送。");
    }

    @PostMapping("/code/verify")
    @Operation(
            summary = "验证登录验证码并创建会话",
            description = "校验一次性邮箱或手机验证码；只有真实账号验证成功且密码策略元数据合格"
                    + "才能创建固定 RT 会话，否则返回 HTTP 409 和 PASSWORD_RESET_REQUIRED。")
    public LoginResponse verifyCode(
            @Valid @RequestBody CodeVerifyRequest body,
            @RequestHeader(FLOW_TOKEN_HEADER) String flowToken,
            @RequestHeader(CHALLENGE_HEADER) String challenge,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        AuthClientPlatform platform = AuthClientPlatform.fromHeader(platformHeader);
        LoginResult result = strategyRegistry.login(
                body.strategyType(),
                new LoginStrategyRequest(
                        null, null, null, null,
                        flowToken, challenge, body.code(), deviceId, canonicalIp(request)));
        return transportResult(result, platform, request, response, false);
    }

    @PostMapping("/totp/verify")
    @Operation(
            summary = "完成登录 TOTP 二次认证",
            description = "仅接受第一因子通过后创建的五分钟一次性挑战；验证成功后才创建刷新会话、"
                    + "签发访问令牌并提升 PreAuth。H5 从 HttpOnly Cookie 读取流程凭据，Android 使用受保护请求头。")
    public LoginResponse verifyTotp(
            @Valid @RequestBody TotpVerifyRequest body,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            @RequestHeader(value = TOTP_FLOW_TOKEN_HEADER, required = false)
                    String androidFlowToken,
            HttpServletRequest request,
            HttpServletResponse response) {
        AuthClientPlatform platform = AuthClientPlatform.fromHeader(platformHeader);
        String rawFlowToken = platform == AuthClientPlatform.ANDROID
                ? androidFlowToken
                : flowCookieWriter.totpLoginFlowToken(request);
        LoginResult result = totpLoginService.verify(
                rawFlowToken, deviceId, body.code());
        return transportResult(result, platform, request, response, true);
    }

    private LoginResponse transportResult(
            LoginResult result,
            AuthClientPlatform platform,
            HttpServletRequest request,
            HttpServletResponse response,
            boolean completingTotp) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, private");
        if (!result.isAuthenticated()) {
            // 第一因子成功但 TOTP 未完成时，只交付短期挑战，禁止创建或提升任何正式会话材料。
            if (platform == AuthClientPlatform.H5) {
                flowCookieWriter.writeTotpLoginFlow(
                        response,
                        result.getTotpFlowToken(),
                        result.getTotpExpiresAt());
            }
            return response(result, platform, null);
        }
        PreAuthIssue preAuth = promotePreAuth(
                request,
                response,
                platform,
                result.getRefreshToken());
        if (platform == AuthClientPlatform.H5) {
            // 必须先完成 PreAuth 旋转再写认证 Cookie，避免旋转失败时提前交付未绑定风险状态的新会话。
            cookieWriter.writeSession(
                    response,
                    result.getAccessToken(),
                    result.getRefreshToken(),
                    result.getCsrfToken(),
                    result.getRefreshExpiresAt());
            if (completingTotp) {
                flowCookieWriter.clearTotpLoginFlow(response);
            }
        }
        return response(result, platform, preAuth);
    }

    private LoginCodeAccess access(
            String flowToken,
            String challenge,
            String deviceId,
            HttpServletRequest request) {
        return new LoginCodeAccess(
                flowToken, challenge, deviceId, canonicalIp(request));
    }

    private String canonicalIp(HttpServletRequest request) {
        return riskContextResolver.resolve(request)
                .map(TrustedNetworkObservation::clientIp)
                .orElseGet(() -> IpAddressIdentity.parse(
                                request.getRemoteAddr())
                        .canonicalText());
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
                    "PreAuth is required after login.",
                    false);
        }
        PreAuthIssue issue = preAuthService.promoteAuthenticated(
                access,
                RiskSessionType.USER_REFRESH,
                refreshToken,
                observation.observedAt());
        if (platform == AuthClientPlatform.H5) {
            preAuthTransport.writeCookie(
                    response,
                    RiskScope.USER,
                    issue.rawToken(),
                    issue.expiresAt(),
                    observation.observedAt());
        }
        return issue;
    }

    private static PreAuthAccess verifiedPreAuthAccess(HttpServletRequest request) {
        Object value = request.getAttribute(NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE);
        return value instanceof PreAuthAccess access ? access : null;
    }

    private static LoginResponse response(
            LoginResult result,
            AuthClientPlatform platform,
            PreAuthIssue preAuthIssue) {
        boolean android = platform == AuthClientPlatform.ANDROID;
        // 只有 Android 响应体保留 Token 字段；H5 的 null 字段由 JsonInclude 省略。
        return new LoginResponse(
                result.getStatus(),
                result.getPublicId(),
                result.getDisplayName(),
                android ? result.getAccessToken() : null,
                android ? result.getRefreshToken() : null,
                android ? result.getCsrfToken() : null,
                android && preAuthIssue != null ? preAuthIssue.rawToken() : null,
                result.getRefreshExpiresAt(),
                android ? result.getTotpFlowToken() : null,
                result.getTotpExpiresAt(),
                result.getAttemptsRemaining());
    }

    public record PasswordLoginRequest(
            @Email @Size(max = 254) String email,
            @Pattern(regexp = "^[A-Za-z]{2}$") String countryIso2,
            @Pattern(regexp = PhoneNumberInputPolicy.BASIC_PHONE_PATTERN) String phoneNumber,
            @Schema(
                    description = "当次登录密码；SHOPPING_V1 低于中等或超过 72 个 UTF-8 字节时要求重置",
                    format = "password",
                    example = "********",
                    accessMode = Schema.AccessMode.WRITE_ONLY)
            @NotBlank @Size(min = 7, max = 72) String password) {
    }

    public record CodeStartRequest(
            LoginStrategyType strategyType,
            @Email @Size(max = 254) String email,
            @Pattern(regexp = "^[A-Za-z]{2}$") String countryIso2,
            @Pattern(regexp = PhoneNumberInputPolicy.BASIC_PHONE_PATTERN) String phoneNumber) {
    }

    public record TurnstileRequest(
            @NotBlank @Size(max = 4096) String turnstileToken) {
    }

    public record CodeSendRequest(VerificationDeliveryMethod deliveryMethod) {
    }

    public record CodeVerifyRequest(
            LoginStrategyType strategyType,
            @NotBlank @Pattern(regexp = "^[0-9]{6}$") String code) {
    }

    public record TotpVerifyRequest(
            @NotBlank @Pattern(regexp = "^[0-9]{6}$") String code) {
    }

    public record FlowAcceptedResponse(boolean accepted, String message) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LoginResponse(
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
