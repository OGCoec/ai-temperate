package com.example.temperate.web.auth.api;

import com.example.temperate.service.auth.login.enums.LoginErrorCode;
import com.example.temperate.service.auth.login.exception.LoginException;
import com.example.temperate.service.auth.passwordreset.PasswordResetErrorCode;
import com.example.temperate.service.auth.passwordreset.PasswordResetException;
import com.example.temperate.service.auth.phonecountry.service.exception.PhoneCountryTimeoutException;
import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.service.humanverification.exception.HumanVerificationUnavailableException;
import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.web.auth.diagnostic.filter.AuthRequestTraceFilter;
import com.example.temperate.web.auth.flow.transport.AuthFlowCookieWriter;
import com.example.temperate.web.auth.session.transport.AuthClientPlatform;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import com.example.temperate.web.risk.PreAuthTransport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Web 层认证与通用异常的集中处理器。
 *
 * <p>用途：将领域异常映射为稳定 HTTP 状态、错误码和对外消息，并在终止性 H5 会话错误时清理认证与 PreAuth Cookie。</p>
 *
 * <p>安全原理：客户端只接收受控错误信息；浏览器 Cookie 清理仅针对 H5 和相应终止性错误执行，避免 Android
 * 请求或临时基础设施故障被错误地扩展为完整会话注销。</p>
 */
@RestControllerAdvice
public final class GlobalExceptionHandler implements AuthExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;
    private final AuthCookieWriter cookieWriter;
    private final AuthFlowCookieWriter flowCookieWriter;
    private final PreAuthTransport preAuthTransport;

    public GlobalExceptionHandler(
            Clock clock,
            AuthCookieWriter cookieWriter,
            AuthFlowCookieWriter flowCookieWriter,
            PreAuthTransport preAuthTransport) {
        this.clock = clock;
        this.cookieWriter = cookieWriter;
        this.flowCookieWriter = flowCookieWriter;
        this.preAuthTransport = preAuthTransport;
    }

    @Override
    @ExceptionHandler(RegistrationException.class)
    public ResponseEntity<ApiErrorResponse> handleRegistration(
            RegistrationException exception,
            HttpServletRequest request,
            HttpServletResponse servletResponse) {
        LOGGER.warn(
                "auth_registration_rejected traceId={} turnstileAttemptId={} inboundCfRay={} "
                        + "businessCode={} diagnosticCode={} cookieHeaderBytes={}",
                diagnosticAttribute(request, AuthRequestTraceFilter.TRACE_ATTRIBUTE),
                diagnosticAttribute(request, AuthRequestTraceFilter.ATTEMPT_ATTRIBUTE),
                diagnosticAttribute(request, AuthRequestTraceFilter.INBOUND_CF_RAY_ATTRIBUTE),
                exception.code(),
                exception.diagnosticCode().map(Enum::name).orElse("absent"),
                diagnosticAttribute(request, AuthRequestTraceFilter.COOKIE_BYTES_ATTRIBUTE));
        clearBrowserRegistrationFlow(exception, request, servletResponse);
        return response(
                registrationStatus(exception.code()),
                exception.code().name(),
                externalRegistrationMessage(exception.code()));
    }

    @Override
    @ExceptionHandler(LoginException.class)
    public ResponseEntity<ApiErrorResponse> handleLogin(LoginException exception) {
        HttpStatus status = switch (exception.code()) {
            case LOGIN_BLOCKED, VERIFICATION_COOLDOWN,
                    VERIFICATION_SEND_LIMIT,
                    TOTP_ATTEMPTS_EXHAUSTED -> HttpStatus.TOO_MANY_REQUESTS;
            case SESSION_LIMIT_REACHED,
                    PASSWORD_RESET_REQUIRED,
                    TOTP_SETUP_SUPERSEDED,
                    TOTP_STATE_CONFLICT -> HttpStatus.CONFLICT;
            case INFRASTRUCTURE_UNAVAILABLE,
                    TOTP_CONFIGURATION_INVALID -> HttpStatus.SERVICE_UNAVAILABLE;
            case TOTP_FLOW_EXPIRED, TOTP_SETUP_EXPIRED -> HttpStatus.GONE;
            case TOTP_STEP_UP_REQUIRED -> HttpStatus.FORBIDDEN;
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.UNAUTHORIZED;
        };
        String message = exception.code() == LoginErrorCode.PASSWORD_RESET_REQUIRED
                ? "密码不符合当前安全策略，请先重置密码。"
                : exception.getMessage();
        return response(status, exception.code().name(), message);
    }

    @Override
    @ExceptionHandler(SessionAuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleSession(
            SessionAuthenticationException exception,
            HttpServletRequest request,
            HttpServletResponse servletResponse) {
        clearBrowserCredentials(exception, request, servletResponse);
        return response(
                sessionStatus(exception.code()),
                externalSessionCode(exception.code()),
                exception.getMessage());
    }

    private static HttpStatus sessionStatus(SessionAuthenticationErrorCode code) {
        return switch (code) {
            case PREAUTH_REQUIRED -> HttpStatus.PRECONDITION_REQUIRED;
            case INFRASTRUCTURE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.UNAUTHORIZED;
        };
    }

    private void clearBrowserCredentials(
            SessionAuthenticationException exception,
            HttpServletRequest request,
            HttpServletResponse response) {
        // 平台头只选择传输协议；只有 H5 的 Cookie 会话才允许由服务端追加清理 Cookie。
        if (AuthClientPlatform.fromHeader(request.getHeader("X-Client-Platform"))
                != AuthClientPlatform.H5) {
            return;
        }
        if (exception.clearCookies()) {
            cookieWriter.clearSession(response);
            preAuthTransport.clearCookie(response, RiskScope.USER);
        }
    }

    @Override
    @ExceptionHandler(PasswordResetException.class)
    public ResponseEntity<ApiErrorResponse> handlePasswordReset(
            PasswordResetException exception,
            HttpServletRequest request,
            HttpServletResponse servletResponse) {
        clearBrowserPasswordResetFlow(exception, request, servletResponse);
        HttpStatus status;
        if (exception.code() == PasswordResetErrorCode.SESSION_REVOCATION_FAILED
                || exception.code() == PasswordResetErrorCode.INFRASTRUCTURE_UNAVAILABLE) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
        } else if (exception.code().name().contains("BLOCKED")
                || exception.code().name().contains("LIMIT")
                || exception.code().name().contains("COOLDOWN")) {
            status = HttpStatus.TOO_MANY_REQUESTS;
        } else if (exception.code().name().contains("EXPIRED")) {
            status = HttpStatus.GONE;
        } else {
            status = HttpStatus.BAD_REQUEST;
        }
        return response(status, exception.code().name(), exception.getMessage());
    }

    private void clearBrowserRegistrationFlow(
            RegistrationException exception,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (AuthClientPlatform.fromHeader(request.getHeader("X-Client-Platform"))
                != AuthClientPlatform.H5) {
            return;
        }
        if (exception.code() == RegistrationErrorCode.REGISTRATION_FLOW_NOT_FOUND
                || exception.code() == RegistrationErrorCode.REGISTRATION_FLOW_EXPIRED
                || exception.code() == RegistrationErrorCode.REGISTRATION_FLOW_FORBIDDEN
                || exception.code() == RegistrationErrorCode.REGISTRATION_PERSISTENCE_FAILED) {
            // 流程已经不可继续时必须清掉 H5 Cookie，防止浏览器持续重放一组必然失败的流程材料。
            flowCookieWriter.clearRegistration(response);
        }
    }

    private void clearBrowserPasswordResetFlow(
            PasswordResetException exception,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (AuthClientPlatform.fromHeader(request.getHeader("X-Client-Platform"))
                != AuthClientPlatform.H5) {
            return;
        }
        if (exception.code() == PasswordResetErrorCode.RESET_FLOW_NOT_FOUND
                || exception.code() == PasswordResetErrorCode.RESET_FLOW_EXPIRED
                || exception.code() == PasswordResetErrorCode.RESET_FLOW_FORBIDDEN
                || exception.code() == PasswordResetErrorCode.FORGET_TOKEN_INVALID
                || exception.code() == PasswordResetErrorCode.SESSION_REVOCATION_FAILED) {
            // resetFlowToken 与 forgetToken 任一失效后都不能继续复用，统一清理两类找回密码 Cookie。
            flowCookieWriter.clearPasswordReset(response);
        }
    }

    @Override
    @ExceptionHandler(PhoneCountryTimeoutException.class)
    public ResponseEntity<ApiErrorResponse> handlePhoneCountryTimeout(
            PhoneCountryTimeoutException exception) {
        // 该 429 表示产品定义的单次查询期限，而不是频率限制，因此只返回稳定错误体且不附加 Retry-After。
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(new ApiErrorResponse(
                        "PHONE_COUNTRY_TIMEOUT",
                        "国家或地区识别超时，请手动选择。",
                        clock.instant()));
    }

    @Override
    @ExceptionHandler(HumanVerificationUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleHumanVerificationUnavailable(
            HumanVerificationUnavailableException exception,
            HttpServletRequest request) {
        // 这里只记录稳定分类和追踪号；底层异常消息可能包含 URI 或网络细节，禁止进入日志模板和响应体。
        LOGGER.warn(
                "auth_human_verification_unavailable traceId={} verificationType={} "
                        + "causeType={}",
                diagnosticAttribute(request, AuthRequestTraceFilter.TRACE_ATTRIBUTE),
                exception.verificationType(),
                exception.getCause() == null
                        ? "none"
                        : exception.getCause().getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(new ApiErrorResponse(
                        "HUMAN_VERIFICATION_UNAVAILABLE",
                        "人机验证服务暂时不可用，请稍后重试。",
                        clock.instant()));
    }

    @Override
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableMessage(
            HttpMessageNotReadableException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "请求参数不正确。");
    }

    @Override
    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        BindException.class,
        ServletRequestBindingException.class,
        HandlerMethodValidationException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiErrorResponse> handleInvalidInput(Exception exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "请求参数不正确。");
    }

    @Override
    @ExceptionHandler(WebInvalidInputException.class)
    public ResponseEntity<ApiErrorResponse> handleWebInvalidInput(
            WebInvalidInputException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "请求参数不正确。");
    }

    @Override
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrity(
            DataIntegrityViolationException exception) {
        return response(
                HttpStatus.CONFLICT,
                "DATA_CONFLICT",
                "当前操作与已有数据冲突，请刷新后重试。");
    }

    /**
     * 将静态资源缺失保留为 404，避免浏览器自动请求 favicon 等资源时被兜底处理误报为系统 500。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleResourceNotFound(
            NoResourceFoundException exception) {
        return response(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "请求资源不存在。");
    }

    @Override
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception exception,
            HttpServletRequest request) {
        // 异常消息可能包含第三方响应或输入片段，只记录追踪号和类型，不把消息或堆栈写入认证日志。
        LOGGER.error(
                "auth_unexpected_exception traceId={} exceptionClass={}",
                diagnosticAttribute(request, AuthRequestTraceFilter.TRACE_ATTRIBUTE),
                exception.getClass().getName());
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "服务暂时不可用，请稍后再试。");
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(code, message, clock.instant()));
    }

    private static String diagnosticAttribute(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        return value == null ? "absent" : value.toString();
    }

    private static HttpStatus registrationStatus(RegistrationErrorCode code) {
        return switch (code) {
            case AUTH_REGISTER_UNAVAILABLE, VERIFICATION_SEND_LIMIT,
                    VERIFICATION_COOLDOWN -> HttpStatus.TOO_MANY_REQUESTS;
            case REGISTRATION_FLOW_NOT_FOUND, REGISTRATION_FLOW_EXPIRED -> HttpStatus.GONE;
            case REGISTRATION_FLOW_FORBIDDEN, TURNSTILE_REJECTED -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    private static String externalRegistrationMessage(RegistrationErrorCode code) {
        return switch (code) {
            case AUTH_REGISTER_UNAVAILABLE -> "暂时无法继续注册，请稍后再试。";
            case VERIFICATION_COOLDOWN -> "验证码发送过于频繁，请稍后再试。";
            case VERIFICATION_SEND_LIMIT -> "验证码发送次数过多，请稍后再试。";
            case VERIFICATION_CODE_INVALID, VERIFICATION_CODE_ATTEMPTS_EXHAUSTED ->
                    "验证码不正确。";
            case VERIFICATION_CODE_EXPIRED -> "验证码已过期，请重新发送。";
            case TURNSTILE_REJECTED, HUMAN_VERIFICATION_REQUIRED ->
                    "请先完成人机验证。";
            case PASSWORD_STRENGTH_INSUFFICIENT ->
                    "密码强度至少需要达到中等，且不得超过 72 个 UTF-8 字节。";
            case REGISTRATION_FLOW_NOT_FOUND, REGISTRATION_FLOW_EXPIRED ->
                    "注册流程已过期，请重新开始。";
            case REGISTRATION_FLOW_FORBIDDEN -> "注册流程无效，请重新开始。";
            default -> "提交内容不正确，请检查后重试。";
        };
    }

    private static String externalSessionCode(SessionAuthenticationErrorCode code) {
        return switch (code) {
            case ACCESS_TOKEN_REQUIRED -> "AT_REQUIRED";
            case ACCESS_TOKEN_INVALID -> "AT_INVALID";
            default -> code.name();
        };
    }
}
