package com.example.temperate.web.auth.api;

import com.example.temperate.service.auth.login.enums.LoginErrorCode;
import com.example.temperate.service.auth.login.exception.LoginException;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowException;
import com.example.temperate.service.auth.oauth.identity.OAuthAccountErrorCode;
import com.example.temperate.service.auth.oauth.identity.OAuthAccountException;
import com.example.temperate.service.auth.oauth.phone.OAuthPhoneRiskException;
import com.example.temperate.service.auth.passwordreset.PasswordResetErrorCode;
import com.example.temperate.service.auth.passwordreset.PasswordResetException;
import com.example.temperate.service.auth.phonecountry.service.exception.PhoneCountryTimeoutException;
import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.service.humanverification.exception.HumanVerificationUnavailableException;
import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.web.auth.diagnostic.filter.AuthRequestTiming;
import com.example.temperate.web.auth.diagnostic.filter.AuthRequestTraceFilter;
import com.example.temperate.web.auth.flow.transport.AuthFlowCookieWriter;
import com.example.temperate.web.auth.oauth.provider.OAuthProviderException;
import com.example.temperate.web.auth.session.transport.AuthClientPlatform;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import com.example.temperate.web.risk.PreAuthTransport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
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
        AuthRequestTiming.recordErrorCode(
                request, externalSessionCode(exception.code()));
        request.setAttribute(
                AuthRequestTiming.CLEAR_COOKIES_ATTRIBUTE,
                exception.clearCookies());
        clearBrowserCredentials(exception, request, servletResponse);
        AuthRequestTiming.writeServerTiming(request, servletResponse);
        return response(
                sessionStatus(exception.code()),
                externalSessionCode(exception.code()),
                exception.getMessage());
    }

    private static HttpStatus sessionStatus(SessionAuthenticationErrorCode code) {
        return switch (code) {
            case PREAUTH_REQUIRED, WEBRTC_VERIFICATION_TIMEOUT ->
                    HttpStatus.PRECONDITION_REQUIRED;
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
     * 将未映射的 API 与静态资源统一保留为 404，防止未知路径落入通用 500 响应。
     *
     * <p>该处理只负责 Spring 已经判定不存在的路由，不维护另一份 Controller 白名单，也不会进入业务 Service。</p>
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiErrorResponse> handleResourceNotFound(
            Exception exception) {
        return response(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "请求资源不存在。");
    }

    @ExceptionHandler(OAuthFlowException.class)
    public ResponseEntity<ApiErrorResponse> handleOAuthFlow(
            OAuthFlowException exception) {
        HttpStatus status = switch (exception.code()) {
            case FLOW_NOT_FOUND, FLOW_EXPIRED -> HttpStatus.GONE;
            case FLOW_FORBIDDEN, STATE_REJECTED, NONCE_REJECTED -> HttpStatus.FORBIDDEN;
            case INVALID_TRANSITION, COMPLETION_IN_PROGRESS, ALREADY_COMPLETED ->
                    HttpStatus.CONFLICT;
            case INFRASTRUCTURE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return response(status, exception.code().name(), "OAuth 登录流程无效或已过期，请重新开始。");
    }

    @ExceptionHandler(OAuthAccountException.class)
    public ResponseEntity<ApiErrorResponse> handleOAuthAccount(
            OAuthAccountException exception) {
        if (exception.code() == OAuthAccountErrorCode.PHONE_UNAVAILABLE) {
            // 对外只表示本次 OAuth 手机号不可用，禁止泄露手机号属于哪个账号。
            return response(
                    HttpStatus.CONFLICT,
                    "OAUTH_PHONE_UNAVAILABLE",
                    "该手机号无法用于本次登录，请更换后重试。");
        }
        HttpStatus status = switch (exception.code()) {
            case ACCOUNT_CONFLICT -> HttpStatus.CONFLICT;
            case ACCOUNT_UNAVAILABLE -> HttpStatus.FORBIDDEN;
            case PERSISTENCE_FAILED -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return response(status, exception.code().name(), "OAuth 账号无法完成登录，请重新开始。");
    }

    @ExceptionHandler(OAuthProviderException.class)
    public ResponseEntity<ApiErrorResponse> handleOAuthProvider(
            OAuthProviderException exception) {
        HttpStatus status = switch (exception.code()) {
            case AUTHORIZATION_REJECTED, PROVIDER_SUBJECT_MISSING,
                    VERIFIED_EMAIL_MISSING, IDENTITY_UNVERIFIED -> HttpStatus.FORBIDDEN;
            case TOKEN_EXCHANGE_FAILED, PROVIDER_UNAVAILABLE -> HttpStatus.BAD_GATEWAY;
        };
        return response(
                status,
                exception.code().name(),
                status == HttpStatus.FORBIDDEN
                        ? "第三方登录凭据未通过验证，请重新开始。"
                        : "第三方登录服务暂时不可用，请稍后重试。");
    }

    @ExceptionHandler(OAuthPhoneRiskException.class)
    public ResponseEntity<ApiErrorResponse> handleOAuthPhoneRisk(
            OAuthPhoneRiskException exception) {
        return response(
                HttpStatus.TOO_MANY_REQUESTS,
                "OAUTH_PHONE_RATE_LIMITED",
                "操作过于频繁，请稍后重试。");
    }

    /**
     * 将已知 Spring 路由的方法错误映射为 405，并把框架计算出的允许方法写入 Allow 响应头。
     *
     * <p>Allow 只来自服务端 Controller 映射，禁止根据客户端输入拼接，因而不会泄露任意请求内容。</p>
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception) {
        ResponseEntity.BodyBuilder response = ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .cacheControl(CacheControl.noStore().cachePrivate());
        Set<HttpMethod> supportedMethods = exception.getSupportedHttpMethods();
        if (supportedMethods != null && !supportedMethods.isEmpty()) {
            response.allow(supportedMethods.toArray(HttpMethod[]::new));
        }
        return response.body(new ApiErrorResponse(
                "METHOD_NOT_ALLOWED",
                "请求方法不受支持。",
                clock.instant()));
    }

    @Override
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception exception,
            HttpServletRequest request) {
        logUnexpectedApiChatSelection(exception, request);
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

    /**
     * 精确 Chat 路径若落入通用处理器，只记录处理器选择和异常类型；MDC 不存在表示诊断关闭，此时保持完全静默。
     */
    private static void logUnexpectedApiChatSelection(
            Exception exception,
            HttpServletRequest request) {
        if (request == null
                || !"POST".equalsIgnoreCase(request.getMethod())
                || !"/v1/chat/completions".equals(request.getRequestURI())) {
            return;
        }
        String traceId = MDC.get("apiChatTraceId");
        if (traceId == null || !traceId.matches("[A-Za-z0-9_-]{1,128}")) {
            return;
        }
        try {
            LOGGER.error(
                    "event=api_chat_unexpected_handler_selected diagnosticSchema=chat-diag-v1 traceId={} handler=GLOBAL_EXCEPTION_HANDLER exceptionType={} rootExceptionType={} status=500",
                    traceId,
                    safeExceptionType(exception),
                    safeRootExceptionType(exception));
        } catch (RuntimeException ignored) {
            // 诊断日志失败不能替换既有通用 500 响应。
        }
    }

    private static String safeRootExceptionType(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && current.getCause() != null && depth++ < 16) {
            current = current.getCause();
        }
        return safeExceptionType(current);
    }

    private static String safeExceptionType(Throwable failure) {
        String value = failure == null ? "none" : failure.getClass().getName();
        return value.matches("[A-Za-z0-9_.$]{1,200}") ? value : "unknown";
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore().cachePrivate())
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
