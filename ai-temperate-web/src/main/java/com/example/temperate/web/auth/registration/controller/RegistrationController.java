package com.example.temperate.web.auth.registration.controller;

import com.example.temperate.common.validation.phone.PhoneNumberInputPolicy;
import com.example.temperate.service.registration.dto.command.RegistrationCompleteCommand;
import com.example.temperate.service.registration.dto.command.RegistrationSendCodeCommand;
import com.example.temperate.service.registration.dto.command.RegistrationStartCommand;
import com.example.temperate.service.registration.dto.command.RegistrationTurnstileCommand;
import com.example.temperate.service.registration.dto.command.RegistrationVerifyCodesCommand;
import com.example.temperate.service.registration.dto.query.RegistrationStatusQuery;
import com.example.temperate.service.registration.dto.result.RegistrationStartResult;
import com.example.temperate.service.registration.dto.result.RegistrationStatusResult;
import com.example.temperate.service.registration.dto.result.VerificationDispatchResult;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.registration.enums.RegistrationStatus;
import com.example.temperate.service.registration.flow.security.RegistrationAccess;
import com.example.temperate.service.registration.service.lifecycle.RegistrationService;
import com.example.temperate.web.auth.flow.transport.AuthFlowCookieWriter;
import com.example.temperate.web.auth.session.transport.AuthClientPlatform;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
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
 * 新用户注册状态机的 HTTP 接口控制器。
 *
 * <p>用途：暴露注册启动、状态恢复、人机校验、双通道验证码和最终开户接口，并将请求头中的流程材料转换为领域命令。</p>
 *
 * <p>安全边界：具体受保护注册操作先经过 {@code RegistrationFlowInterceptor} 校验流程、设备、挑战和流程 CSRF；
 * Controller 不自行信任或持久化这些原始材料。</p>
 */
@RestController
@RequestMapping("/api/auth/register")
@Tag(
        name = "认证-新用户注册",
        description = "新用户注册端到端流程：校验邮箱与国际手机号、完成 Turnstile、发送并组合校验双验证码、设置密码后入库；注册成功不自动登录。")
public final class RegistrationController {

    public static final String DEVICE_HEADER = "X-Device-Installation-Id";
    public static final String PLATFORM_HEADER = "X-Client-Platform";
    public static final String TOKEN_HEADER = "X-Register-Token";
    public static final String FLOW_CSRF_HEADER = "X-Register-CSRF";
    public static final String CHALLENGE_HEADER = "X-Turnstile-Challenge";

    private final RegistrationService registrationService;
    private final AuthFlowCookieWriter flowCookieWriter;

    public RegistrationController(
            RegistrationService registrationService,
            AuthFlowCookieWriter flowCookieWriter) {
        this.registrationService = registrationService;
        this.flowCookieWriter = flowCookieWriter;
    }

    @PostMapping("/start")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "开始注册并签发十分钟空闲有效的注册流程令牌")
    public StartResponse start(
            @Valid @RequestBody StartRequest body,
            @RequestHeader(DEVICE_HEADER) String deviceInstallationId,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        RegistrationStartResult result = registrationService.start(new RegistrationStartCommand(
                body.email(),
                body.countryIso2(),
                body.phoneNumber(),
                deviceInstallationId,
                request.getRemoteAddr()));
        AuthClientPlatform platform = AuthClientPlatform.fromHeader(platformHeader);
        if (!platform.usesExplicitTokenTransport()) {
            // H5 流程凭据只写入 HttpOnly 会话 Cookie，响应 JSON 不再暴露 registerToken 和 flowCsrf。
            flowCookieWriter.writeRegistration(
                    response,
                    result.registerToken(),
                    result.flowCsrf(),
                    result.challengeHandle());
        }
        return startResponse(result, platform);
    }

    @GetMapping("/status")
    @Operation(summary = "恢复注册流程状态并续签允许接口的空闲期限")
    public RegistrationStatusResponse status(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FLOW_CSRF_HEADER, required = false) String flowCsrf,
            @RequestHeader(value = CHALLENGE_HEADER, required = false) String challenge,
            @RequestHeader(DEVICE_HEADER) String device,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        RegistrationAccess access = access(token, flowCsrf, challenge, device, platformHeader,
                request);
        preventSensitiveResponseCaching(response);
        return statusResponse(registrationService.status(new RegistrationStatusQuery(access)),
                access.challengeHandle());
    }

    @PostMapping("/turnstile")
    @Operation(summary = "服务端校验注册流程的 Cloudflare Turnstile 响应")
    public Mono<RegistrationStatusResponse> turnstile(
            @Valid @RequestBody TurnstileRequest body,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FLOW_CSRF_HEADER, required = false) String flowCsrf,
            @RequestHeader(value = CHALLENGE_HEADER, required = false) String challenge,
            @RequestHeader(DEVICE_HEADER) String device,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        RegistrationAccess access = access(token, flowCsrf, challenge, device, platformHeader,
                request);
        preventSensitiveResponseCaching(response);
        return registrationService.verifyTurnstile(
                        new RegistrationTurnstileCommand(
                                access, body.turnstileToken()))
                .map(result -> statusResponse(
                        result, access.challengeHandle()));
    }

    @PostMapping("/codes/email/send")
    @Operation(summary = "发送注册邮箱验证码")
    public VerificationDispatchResult sendEmail(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FLOW_CSRF_HEADER, required = false) String flowCsrf,
            @RequestHeader(value = CHALLENGE_HEADER, required = false) String challenge,
            @RequestHeader(DEVICE_HEADER) String device,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request) {
        return send(token, flowCsrf, challenge, device, platformHeader, request,
                VerificationChannel.EMAIL, VerificationDeliveryMethod.EMAIL);
    }

    @PostMapping("/codes/sms/send")
    @Operation(summary = "发送注册短信验证码")
    public VerificationDispatchResult sendSms(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FLOW_CSRF_HEADER, required = false) String flowCsrf,
            @RequestHeader(value = CHALLENGE_HEADER, required = false) String challenge,
            @RequestHeader(DEVICE_HEADER) String device,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request) {
        return send(token, flowCsrf, challenge, device, platformHeader, request,
                VerificationChannel.SMS, VerificationDeliveryMethod.SMS);
    }

    @PostMapping("/codes/phone/send")
    @Operation(
            summary = "选择短信或 WhatsApp 发送注册手机验证码",
            description = "中国大陆手机号只支持 SMS；其他有效国际手机号可选择 SMS 或 WhatsApp。")
    public VerificationDispatchResult sendPhone(
            @Valid @RequestBody PhoneCodeSendRequest body,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FLOW_CSRF_HEADER, required = false) String flowCsrf,
            @RequestHeader(value = CHALLENGE_HEADER, required = false) String challenge,
            @RequestHeader(DEVICE_HEADER) String device,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request) {
        return send(
                token,
                flowCsrf,
                challenge,
                device,
                platformHeader,
                request,
                VerificationChannel.SMS,
                body.deliveryMethod());
    }

    @PostMapping("/codes/verify")
    @Operation(summary = "一次提交邮箱与手机验证码并要求两者同时正确")
    public RegistrationStatusResponse verifyCodes(
            @Valid @RequestBody VerifyCodesRequest body,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FLOW_CSRF_HEADER, required = false) String flowCsrf,
            @RequestHeader(value = CHALLENGE_HEADER, required = false) String challenge,
            @RequestHeader(DEVICE_HEADER) String device,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        RegistrationAccess access = access(token, flowCsrf, challenge, device, platformHeader,
                request);
        preventSensitiveResponseCaching(response);
        return statusResponse(registrationService.verifyCodes(new RegistrationVerifyCodesCommand(
                access, body.emailCode(), body.smsCode())), access.challengeHandle());
    }

    @PostMapping("/complete")
    @Operation(
            summary = "设置密码并完成注册，成功后进入登录流程",
            description = "密码必须按 SHOPPING_V1 至少达到中等且不超过 72 个 UTF-8 字节；"
                    + "不合格时返回 HTTP 400 和 PASSWORD_STRENGTH_INSUFFICIENT，不写入用户。")
    public RegistrationCompleteResponse complete(
            @Valid @RequestBody CompleteRequest body,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = FLOW_CSRF_HEADER, required = false) String flowCsrf,
            @RequestHeader(value = CHALLENGE_HEADER, required = false) String challenge,
            @RequestHeader(DEVICE_HEADER) String device,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        RegistrationAccess access = access(token, flowCsrf, challenge, device, platformHeader,
                request);
        registrationService.complete(new RegistrationCompleteCommand(
                access,
                body.password(),
                body.passwordConfirmation()));
        if (!AuthClientPlatform.fromHeader(platformHeader).usesExplicitTokenTransport()) {
            flowCookieWriter.clearRegistration(response);
        }
        return new RegistrationCompleteResponse(true, "LOGIN");
    }

    private VerificationDispatchResult send(
            String token,
            String flowCsrf,
            String challenge,
            String device,
            String platformHeader,
            HttpServletRequest request,
            VerificationChannel channel,
            VerificationDeliveryMethod deliveryMethod) {
        return registrationService.sendCode(new RegistrationSendCodeCommand(
                access(token, flowCsrf, challenge, device, platformHeader, request),
                channel,
                deliveryMethod));
    }

    private RegistrationAccess access(
            String token,
            String flowCsrf,
            String challenge,
            String device,
            String platformHeader,
            HttpServletRequest request) {
        if (!AuthClientPlatform.fromHeader(platformHeader).usesExplicitTokenTransport()) {
            AuthFlowCookieWriter.RegistrationFlowCookies cookies =
                    flowCookieWriter.registration(request);
            return new RegistrationAccess(
                    cookies.registerToken(),
                    cookies.flowCsrf(),
                    cookies.challengeHandle(),
                    device,
                    request.getRemoteAddr());
        }
        return new RegistrationAccess(
                token, flowCsrf, challenge, device, request.getRemoteAddr());
    }

    private static StartResponse startResponse(
            RegistrationStartResult result,
            AuthClientPlatform platform) {
        boolean explicit = platform.usesExplicitTokenTransport();
        return new StartResponse(
                explicit ? result.registerToken() : null,
                explicit ? result.flowCsrf() : null,
                result.challengeHandle(),
                result.expiresAt());
    }

    private static RegistrationStatusResponse statusResponse(
            RegistrationStatusResult result,
            String challengeHandle) {
        // 联系方式只在人机验证通过后进入响应，避免尚未完成安全检查的流程通过状态接口读取明文目标。
        String email = result.humanVerified() ? result.email() : null;
        String phoneE164 = result.humanVerified() ? result.phoneE164() : null;
        return new RegistrationStatusResponse(
                result.status(),
                result.humanVerified(),
                result.emailVerified(),
                result.phoneVerified(),
                result.createdAt(),
                result.expiresAt(),
                result.absoluteExpiresAt(),
                challengeHandle,
                email,
                phoneE164);
    }

    private static void preventSensitiveResponseCaching(HttpServletResponse response) {
        // 已验证联系方式属于短期敏感响应，浏览器和中间缓存都不得持久保存。
        response.setHeader(
                HttpHeaders.CACHE_CONTROL,
                CacheControl.noStore().cachePrivate().getHeaderValue());
    }

    public record StartRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$") String countryIso2,
            @NotBlank @Pattern(regexp = PhoneNumberInputPolicy.BASIC_PHONE_PATTERN) String phoneNumber) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StartResponse(
            String registerToken,
            String flowCsrf,
            String challengeHandle,
            Instant expiresAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RegistrationStatusResponse(
            RegistrationStatus status,
            boolean humanVerified,
            boolean emailVerified,
            boolean phoneVerified,
            Instant createdAt,
            Instant expiresAt,
            Instant absoluteExpiresAt,
            String challengeHandle,
            @Schema(
                    description = "人机验证通过后返回的规范化邮箱，仅用于只读确认。",
                    example = "u***@example.test",
                    accessMode = Schema.AccessMode.READ_ONLY)
            String email,
            @Schema(
                    description = "人机验证通过后返回的 E.164 手机号，仅用于只读确认。",
                    example = "+1******2671",
                    accessMode = Schema.AccessMode.READ_ONLY)
            String phoneE164) {
    }

    public record TurnstileRequest(
            @NotBlank @Size(max = 4096) String turnstileToken) {
    }

    public record PhoneCodeSendRequest(
            @NotNull VerificationDeliveryMethod deliveryMethod) {
    }

    public record VerifyCodesRequest(
            @NotBlank @Pattern(regexp = "^[0-9]{6}$") String emailCode,
            @NotBlank @Pattern(regexp = "^[0-9]{6}$") String smsCode) {
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

    public record RegistrationCompleteResponse(boolean registered, String nextAction) {
    }
}
