package com.example.temperate.web.auth.passwordreset.controller;

import com.example.temperate.common.validation.phone.PhoneNumberInputPolicy;
import com.example.temperate.service.auth.passwordreset.dto.ForgetTokenResult;
import com.example.temperate.service.auth.passwordreset.dto.PasswordResetAccess;
import com.example.temperate.service.auth.passwordreset.dto.PasswordResetStartCommand;
import com.example.temperate.service.auth.passwordreset.dto.PasswordResetStartResult;
import com.example.temperate.service.auth.passwordreset.service.PasswordResetService;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.web.auth.flow.transport.AuthFlowCookieWriter;
import com.example.temperate.web.auth.session.transport.AuthClientPlatform;
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
import java.time.Instant;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 密码找回与重置流程的 HTTP 接口控制器。
 *
 * <p>用途：编排找回流程启动、人机校验、验证码发送与校验、一次性找回凭据签发和最终密码重置。</p>
 *
 * <p>安全边界：接口只传递短期流程材料给领域服务；重置成功后不自动登录，避免将密码重置结果错误扩展为
 * 新会话签发。</p>
 */
@RestController
@RequestMapping("/api/auth/password-reset")
@Tag(
        name = "认证-找回密码",
        description = "用户忘记密码后的安全重置流程：支持邮箱或国际手机号二选一、每个流程完成一次 Turnstile、发送并校验验证码、签发五分钟一次性凭证并重置密码；成功后撤销全部会话且不自动登录。")
public final class PasswordResetController {

    private static final String DEVICE_HEADER = "X-Device-Installation-Id";
    private static final String PLATFORM_HEADER = "X-Client-Platform";
    private static final String FLOW_TOKEN_HEADER = "X-Reset-Flow-Token";
    private static final String CHALLENGE_HEADER = "X-Turnstile-Challenge";
    private static final String FORGET_TOKEN_HEADER = "X-Forget-Token";

    private final PasswordResetService passwordResetService;
    private final AuthFlowCookieWriter flowCookieWriter;

    public PasswordResetController(
            PasswordResetService passwordResetService,
            AuthFlowCookieWriter flowCookieWriter) {
        this.passwordResetService = passwordResetService;
        this.flowCookieWriter = flowCookieWriter;
    }

    @PostMapping("/start")
    @Operation(summary = "选择邮箱或手机号并开始找回密码流程")
    public PasswordResetStartResponse start(
            @Valid @RequestBody StartRequest body,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        PasswordResetStartResult result = passwordResetService.start(new PasswordResetStartCommand(
                body.channel(),
                body.email(),
                body.countryIso2(),
                body.phoneNumber(),
                deviceId,
                request.getRemoteAddr()));
        AuthClientPlatform platform = AuthClientPlatform.fromHeader(platformHeader);
        if (platform == AuthClientPlatform.H5) {
            // H5 找回密码流程令牌只写入 HttpOnly Cookie，避免响应 JSON 暴露 resetFlowToken。
            flowCookieWriter.writePasswordResetFlow(
                    response, result.resetFlowToken(), result.expiresAt());
        }
        return startResponse(result, platform);
    }

    @PostMapping("/turnstile")
    @Operation(summary = "服务端校验找回密码流程的 Cloudflare Turnstile")
    public Mono<AcceptedResponse> verifyTurnstile(
            @Valid @RequestBody TurnstileRequest body,
            @RequestHeader(value = FLOW_TOKEN_HEADER, required = false) String flowToken,
            @RequestHeader(CHALLENGE_HEADER) String challenge,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request) {
        return passwordResetService.verifyTurnstile(
                        access(
                                flowToken,
                                challenge,
                                deviceId,
                                platformHeader,
                                request),
                        body.turnstileToken())
                .thenReturn(new AcceptedResponse(
                        true, "人机验证已通过，可以手动发送验证码。"));
    }

    @PostMapping("/send")
    @Operation(summary = "向所选邮箱或手机号发送找回密码验证码")
    public AcceptedResponse send(
            @Valid @RequestBody(required = false) CodeSendRequest body,
            @RequestHeader(value = FLOW_TOKEN_HEADER, required = false) String flowToken,
            @RequestHeader(CHALLENGE_HEADER) String challenge,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request) {
        passwordResetService.sendCode(
                access(flowToken, challenge, deviceId, platformHeader, request),
                body == null ? null : body.deliveryMethod());
        return new AcceptedResponse(true, "如果账号存在，验证码已经发送。");
    }

    @PostMapping("/verify")
    @Operation(summary = "校验找回密码验证码并签发五分钟一次性凭证")
    public ForgetTokenResponse verify(
            @Valid @RequestBody VerifyRequest body,
            @RequestHeader(value = FLOW_TOKEN_HEADER, required = false) String flowToken,
            @RequestHeader(CHALLENGE_HEADER) String challenge,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        ForgetTokenResult result = passwordResetService.verifyCode(
                access(flowToken, challenge, deviceId, platformHeader, request), body.code());
        AuthClientPlatform platform = AuthClientPlatform.fromHeader(platformHeader);
        if (platform == AuthClientPlatform.H5) {
            // forgetToken 是一次性完成凭证，H5 仅通过专用 HttpOnly Cookie 携带到 complete 端点。
            flowCookieWriter.writeForgetToken(
                    response, result.forgetToken(), result.expiresAt());
        }
        return forgetTokenResponse(result, platform);
    }

    @PostMapping("/complete")
    @Operation(
            summary = "使用一次性凭证设置新密码并返回登录页",
            description = "密码必须按 SHOPPING_V1 至少达到中等且不超过 72 个 UTF-8 字节；"
                    + "不合格时返回 HTTP 400 和 PASSWORD_STRENGTH_INSUFFICIENT，不修改密码。")
    public CompleteResponse complete(
            @Valid @RequestBody CompleteRequest body,
            @RequestHeader(value = FORGET_TOKEN_HEADER, required = false) String forgetToken,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        String resolvedForgetToken = AuthClientPlatform.fromHeader(platformHeader)
                == AuthClientPlatform.H5 ? flowCookieWriter.forgetToken(request) : forgetToken;
        passwordResetService.complete(
                resolvedForgetToken, deviceId, body.password(), body.passwordConfirmation());
        if (AuthClientPlatform.fromHeader(platformHeader) == AuthClientPlatform.H5) {
            flowCookieWriter.clearPasswordReset(response);
        }
        return new CompleteResponse(true, "LOGIN");
    }

    private PasswordResetAccess access(
            String flowToken,
            String challenge,
            String deviceId,
            String platformHeader,
            HttpServletRequest request) {
        if (AuthClientPlatform.fromHeader(platformHeader) == AuthClientPlatform.H5) {
            return new PasswordResetAccess(
                    flowCookieWriter.resetFlowToken(request),
                    challenge,
                    deviceId,
                    request.getRemoteAddr());
        }
        return new PasswordResetAccess(
                flowToken, challenge, deviceId, request.getRemoteAddr());
    }

    private static PasswordResetStartResponse startResponse(
            PasswordResetStartResult result,
            AuthClientPlatform platform) {
        return new PasswordResetStartResponse(
                platform == AuthClientPlatform.ANDROID ? result.resetFlowToken() : null,
                result.challengeHandle(),
                result.expiresAt());
    }

    private static ForgetTokenResponse forgetTokenResponse(
            ForgetTokenResult result,
            AuthClientPlatform platform) {
        return new ForgetTokenResponse(
                platform == AuthClientPlatform.ANDROID ? result.forgetToken() : null,
                result.expiresAt());
    }

    public record StartRequest(
            @NotNull VerificationChannel channel,
            @Email @Size(max = 254) String email,
            @Pattern(regexp = "^[A-Za-z]{2}$") String countryIso2,
            @Pattern(regexp = PhoneNumberInputPolicy.BASIC_PHONE_PATTERN) String phoneNumber) {
    }

    public record TurnstileRequest(
            @NotBlank @Size(max = 4096) String turnstileToken) {
    }

    public record CodeSendRequest(VerificationDeliveryMethod deliveryMethod) {
    }

    public record VerifyRequest(
            @NotBlank @Pattern(regexp = "^[0-9]{6}$") String code) {
    }

    public record CompleteRequest(
            @Schema(
                    description = "SHOPPING_V1 强度至少为中且最多 72 个 UTF-8 字节的新密码",
                    format = "password",
                    example = "********",
                    accessMode = Schema.AccessMode.WRITE_ONLY)
            String password,
            @Schema(
                    description = "必须与新密码完全一致的确认值",
                    format = "password",
                    example = "********",
                    accessMode = Schema.AccessMode.WRITE_ONLY)
            String passwordConfirmation) {
    }

    public record AcceptedResponse(boolean accepted, String message) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PasswordResetStartResponse(
            String resetFlowToken,
            String challengeHandle,
            Instant expiresAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ForgetTokenResponse(String forgetToken, Instant expiresAt) {
    }

    public record CompleteResponse(boolean passwordReset, String nextAction) {
    }
}
