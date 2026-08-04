package com.example.temperate.web.user.security.controller;

import com.example.temperate.common.net.ip.IpAddressIdentity;
import com.example.temperate.service.auth.login.code.dto.LoginCodeAccess;
import com.example.temperate.service.auth.login.code.dto.LoginCodeStartResult;
import com.example.temperate.service.auth.login.code.service.LoginCodeFlowService;
import com.example.temperate.service.auth.login.strategy.LoginStrategyType;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.auth.totp.management.TotpManagementAction;
import com.example.temperate.service.auth.totp.management.TotpManagementService;
import com.example.temperate.service.auth.totp.management.dto.TotpSetupResult;
import com.example.temperate.service.auth.totp.management.dto.TotpStateChangeResult;
import com.example.temperate.service.auth.totp.management.dto.TotpStatusResult;
import com.example.temperate.service.auth.totp.stepup.TotpStepUpService;
import com.example.temperate.service.auth.totp.stepup.dto.TotpStepUpProofResult;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.web.auth.session.transport.AuthClientPlatform;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import com.example.temperate.web.risk.RiskRequestContextResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 为当前已登录用户提供 TOTP 状态查询、第一因子复验、设置确认与关闭接口。
 *
 * <p>用户身份只来自已经完成 RT-first 校验的安全上下文；开启、轮换和关闭均要求绑定用户、设备和动作的一次性
 * step-up 凭证，轮换与关闭还必须校验当前 TOTP。控制器不接收用户 ID，也不返回数据库密文。</p>
 */
@RestController
@RequestMapping("/api/users/me/security/totp")
@Tag(
        name = "认证-当前用户二次认证",
        description = "为已通过 RT-first 会话认证的当前用户查询、开启、轮换和关闭 TOTP。"
                + "设置使用十分钟待确认密钥，只有六位动态码校验成功后才写库；"
                + "敏感操作要求密码、邮箱码或短信码复验，不负责登录第一因子和恢复码。")
public final class CurrentUserTotpController {

    private static final String DEVICE_HEADER = "X-Device-Installation-Id";
    private static final String PLATFORM_HEADER = "X-Client-Platform";
    private static final String FLOW_TOKEN_HEADER = "X-Login-Flow-Token";
    private static final String CHALLENGE_HEADER = "X-Turnstile-Challenge";

    private final TotpManagementService managementService;
    private final TotpStepUpService stepUpService;
    private final LoginCodeFlowService codeFlowService;
    private final AuthCookieWriter cookieWriter;
    private final RiskRequestContextResolver riskContextResolver;

    public CurrentUserTotpController(
            TotpManagementService managementService,
            TotpStepUpService stepUpService,
            LoginCodeFlowService codeFlowService,
            AuthCookieWriter cookieWriter,
            RiskRequestContextResolver riskContextResolver) {
        this.managementService = managementService;
        this.stepUpService = stepUpService;
        this.codeFlowService = codeFlowService;
        this.cookieWriter = cookieWriter;
        this.riskContextResolver = riskContextResolver;
    }

    @GetMapping
    @Operation(
            summary = "查询当前用户 TOTP 状态",
            description = "只返回是否启用，不返回密钥、密文、二维码 URI 或内部用户 ID；响应禁止缓存。")
    public ResponseEntity<TotpStatusResult> status(
            @AuthenticationPrincipal SessionPrincipal principal) {
        return noStore(managementService.status(principal.userId()));
    }

    @PostMapping("/reverification/password")
    @Operation(
            summary = "使用当前密码完成安全复验",
            description = "验证成功后签发五分钟、绑定当前用户/设备/目标动作且只能消费一次的 step-up token。")
    public ResponseEntity<TotpStepUpProofResult> reverifyPassword(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @Valid @RequestBody PasswordReverificationRequest body) {
        return noStore(stepUpService.verifyPassword(
                principal.userId(), deviceId, body.action(), body.password()));
    }

    @PostMapping("/reverification/code/start")
    @Operation(
            summary = "开始邮箱码或短信码安全复验",
            description = "验证码投递目标只从当前已认证用户数据库资料读取，客户端不能提交或替换邮箱、手机号。")
    public ResponseEntity<LoginCodeStartResult> startCodeReverification(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @Valid @RequestBody CodeReverificationStartRequest body,
            HttpServletRequest request) {
        return noStore(stepUpService.startCode(
                principal.userId(),
                deviceId,
                canonicalIp(request),
                body.action(),
                body.strategyType()));
    }

    @PostMapping("/reverification/code/turnstile")
    @Operation(
            summary = "验证安全复验流程的人机挑战",
            description = "复用登录验证码流程的服务端 Turnstile 校验，但不会因此签发登录会话。")
    public Mono<ResponseEntity<AcceptedResponse>> verifyCodeTurnstile(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader(FLOW_TOKEN_HEADER) String flowToken,
            @RequestHeader(CHALLENGE_HEADER) String challenge,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @Valid @RequestBody TurnstileRequest body,
            HttpServletRequest request) {
        return codeFlowService.verifyTurnstile(
                        access(flowToken, challenge, deviceId, request),
                        body.turnstileToken())
                .thenReturn(noStore(new AcceptedResponse(true)));
    }

    @PostMapping("/reverification/code/send")
    @Operation(
            summary = "发送安全复验验证码",
            description = "人机验证通过后按已经绑定的账号渠道投递，并沿用登录验证码的频率、失败次数和防重放限制。")
    public ResponseEntity<AcceptedResponse> sendCode(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader(FLOW_TOKEN_HEADER) String flowToken,
            @RequestHeader(CHALLENGE_HEADER) String challenge,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @Valid @RequestBody(required = false) CodeSendRequest body,
            HttpServletRequest request) {
        codeFlowService.sendCode(
                access(flowToken, challenge, deviceId, request),
                body == null ? null : body.deliveryMethod());
        return noStore(new AcceptedResponse(true));
    }

    @PostMapping("/reverification/code/verify")
    @Operation(
            summary = "校验安全复验验证码",
            description = "原子消费六位验证码后签发五分钟一次性 step-up token；不会创建 Access Token 或 Refresh Token。")
    public ResponseEntity<TotpStepUpProofResult> verifyCode(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader(FLOW_TOKEN_HEADER) String flowToken,
            @RequestHeader(CHALLENGE_HEADER) String challenge,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @Valid @RequestBody CodeReverificationVerifyRequest body,
            HttpServletRequest request) {
        return noStore(stepUpService.verifyCode(
                principal.userId(),
                deviceId,
                canonicalIp(request),
                body.action(),
                body.strategyType(),
                flowToken,
                challenge,
                body.code()));
    }

    @PostMapping("/setup/start")
    @Operation(
            summary = "生成待确认 TOTP 新密钥",
            description = "消费对应动作的 step-up token；返回 Base32 密钥和 otpauth URI 供客户端本地生成二维码。"
                    + "密钥只以密文暂存 Redis 十分钟，确认前不修改数据库。")
    public ResponseEntity<TotpSetupResult> startSetup(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @Valid @RequestBody SetupStartRequest body) {
        return noStore(managementService.startSetup(
                principal.userId(),
                deviceId,
                body.action(),
                body.stepUpToken(),
                body.currentTotpCode()));
    }

    @PostMapping("/setup/confirm")
    @Operation(
            summary = "确认开启或轮换 TOTP",
            description = "校验新认证器生成的六位动态码后，才在同一数据库更新中写入密文并设为启用；"
                    + "成功后撤销全部 Refresh Session，客户端必须重新登录。")
    public ResponseEntity<TotpStateChangeResult> confirmSetup(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            @Valid @RequestBody SetupConfirmRequest body,
            HttpServletResponse response) {
        TotpStateChangeResult result = managementService.confirmSetup(
                principal.userId(), deviceId, body.setupToken(), body.code());
        clearBrowserSession(platformHeader, response);
        return noStore(result);
    }

    @PostMapping("/disable")
    @Operation(
            summary = "关闭当前用户 TOTP",
            description = "消费 DISABLE step-up token 并验证当前 TOTP 后，在同一 SQL 中设为未启用并清空密文；"
                    + "成功后撤销全部 Refresh Session，客户端必须重新登录。")
    public ResponseEntity<TotpStateChangeResult> disable(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            @Valid @RequestBody DisableRequest body,
            HttpServletResponse response) {
        TotpStateChangeResult result = managementService.disable(
                principal.userId(),
                deviceId,
                body.stepUpToken(),
                body.currentTotpCode());
        clearBrowserSession(platformHeader, response);
        return noStore(result);
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

    private void clearBrowserSession(
            String platformHeader,
            HttpServletResponse response) {
        if (AuthClientPlatform.fromHeader(platformHeader) == AuthClientPlatform.H5) {
            cookieWriter.clearSession(response);
        }
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(body);
    }

    public record PasswordReverificationRequest(
            @NotNull TotpManagementAction action,
            @Schema(
                    description = "当前账号密码，仅用于本次安全复验",
                    format = "password",
                    example = "********",
                    accessMode = Schema.AccessMode.WRITE_ONLY)
            @NotBlank @Size(min = 7, max = 72) String password) {
    }

    public record CodeReverificationStartRequest(
            @NotNull TotpManagementAction action,
            @NotNull LoginStrategyType strategyType) {
    }

    public record TurnstileRequest(
            @NotBlank @Size(max = 4096) String turnstileToken) {
    }

    public record CodeSendRequest(VerificationDeliveryMethod deliveryMethod) {
    }

    public record CodeReverificationVerifyRequest(
            @NotNull TotpManagementAction action,
            @NotNull LoginStrategyType strategyType,
            @NotBlank @Pattern(regexp = "^[0-9]{6}$") String code) {
    }

    public record SetupStartRequest(
            @NotNull TotpManagementAction action,
            @Schema(
                    description = "密码、邮箱码或短信码复验后签发的一次性凭证",
                    example = "step-up-token-redacted",
                    accessMode = Schema.AccessMode.WRITE_ONLY)
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{38}$") String stepUpToken,
            @Pattern(regexp = "^[0-9]{6}$") String currentTotpCode) {
    }

    public record SetupConfirmRequest(
            @Schema(
                    description = "生成待确认密钥时签发的十分钟一次性凭证",
                    example = "setup-token-redacted",
                    accessMode = Schema.AccessMode.WRITE_ONLY)
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{38}$") String setupToken,
            @NotBlank @Pattern(regexp = "^[0-9]{6}$") String code) {
    }

    public record DisableRequest(
            @Schema(
                    description = "DISABLE 动作复验后签发的一次性凭证",
                    example = "step-up-token-redacted",
                    accessMode = Schema.AccessMode.WRITE_ONLY)
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{38}$") String stepUpToken,
            @NotBlank @Pattern(regexp = "^[0-9]{6}$") String currentTotpCode) {
    }

    public record AcceptedResponse(boolean accepted) {
    }
}
