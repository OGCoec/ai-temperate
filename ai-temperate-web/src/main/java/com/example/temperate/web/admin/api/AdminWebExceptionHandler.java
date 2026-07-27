package com.example.temperate.web.admin.api;

import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.risk.ip2location.exception.Ip2LocationApiKeyCapacityExceededException;
import com.example.temperate.web.admin.security.AdminClientPlatformResolver;
import com.example.temperate.web.admin.transport.AdminCookieWriter;
import com.example.temperate.web.auth.api.ApiErrorResponse;
import com.example.temperate.web.auth.session.transport.AuthClientPlatform;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将管理员领域异常映射为稳定 HTTP 状态和脱敏错误体，并清理已终止的 H5 Flow 或会话 Cookie。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public final class AdminWebExceptionHandler {

    private final Clock clock;
    private final AdminCookieWriter cookieWriter;
    private final AdminClientPlatformResolver platformResolver;
    private final AdminExceptionLogger exceptionLogger;

    public AdminWebExceptionHandler(
            Clock clock,
            AdminCookieWriter cookieWriter,
            AdminClientPlatformResolver platformResolver,
            AdminExceptionLogger exceptionLogger) {
        this.clock = Objects.requireNonNull(clock);
        this.cookieWriter = Objects.requireNonNull(cookieWriter);
        this.platformResolver = Objects.requireNonNull(platformResolver);
        this.exceptionLogger = Objects.requireNonNull(exceptionLogger);
    }

    @ExceptionHandler(AdminException.class)
    public ResponseEntity<ApiErrorResponse> handle(
            AdminException exception,
            HttpServletRequest request,
            HttpServletResponse response) {
        HttpStatus httpStatus = status(exception.code());
        exceptionLogger.logKnown(
                "admin_auth_rejected",
                exception.code().name(),
                httpStatus,
                exception);
        if (platformResolver.resolve(request) == AuthClientPlatform.H5) {
            if (exception.clearFlow()) {
                cookieWriter.clearRegistration(response);
                cookieWriter.clearLogin(response);
            }
            if (exception.clearSession()) {
                cookieWriter.clearSession(response);
            }
        }
        return ResponseEntity.status(httpStatus)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(new ApiErrorResponse(
                        exception.code().name(),
                        message(exception.code()),
                        clock.instant()));
    }

    /**
     * 将 Redis 原子容量竞争转换为稳定冲突响应，不向管理员端回显本批 Key 或内部 Hash 信息。
     */
    @ExceptionHandler(Ip2LocationApiKeyCapacityExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleIp2LocationCapacity(
            Ip2LocationApiKeyCapacityExceededException exception) {
        HttpStatus httpStatus = HttpStatus.CONFLICT;
        exceptionLogger.logKnown(
                "admin_ip2location_key_capacity_reached",
                "IP2LOCATION_KEY_LIMIT_EXCEEDED",
                httpStatus,
                exception);
        return ResponseEntity.status(httpStatus)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(new ApiErrorResponse(
                        "IP2LOCATION_KEY_LIMIT_EXCEEDED",
                        "IP2Location 凭据池已达到 100 条上限，请先删除不再使用的凭据。",
                        clock.instant()));
    }

    private static HttpStatus status(AdminErrorCode code) {
        return switch (code) {
            case ADMIN_ALREADY_INITIALIZED, ADMIN_NOT_INITIALIZED,
                    ADMIN_SESSION_LIMIT_REACHED -> HttpStatus.CONFLICT;
            case ADMIN_CONFIG_INVALID, ADMIN_CSRF_CONFIGURATION_INVALID,
                    ADMIN_DISABLED, HCAPTCHA_UNAVAILABLE,
                    ADMIN_INFRASTRUCTURE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case ADMIN_FLOW_EXPIRED -> HttpStatus.GONE;
            case ADMIN_PREAUTH_REQUIRED -> HttpStatus.PRECONDITION_REQUIRED;
            case HCAPTCHA_REJECTED -> HttpStatus.FORBIDDEN;
            case ADMIN_CREDENTIALS_INVALID, ADMIN_SESSION_INVALID,
                    ADMIN_FLOW_INVALID -> HttpStatus.UNAUTHORIZED;
            case ADMIN_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    private static String message(AdminErrorCode code) {
        return switch (code) {
            case ADMIN_ALREADY_INITIALIZED -> "管理员已经完成初始化。";
            case ADMIN_NOT_INITIALIZED -> "管理员尚未初始化。";
            case ADMIN_CONFIG_INVALID -> "管理员配置无法安全读取，请检查隐藏配置文件。";
            case ADMIN_CSRF_CONFIGURATION_INVALID ->
                    "管理员安全 Cookie 作用域配置无效，请联系管理员。";
            case ADMIN_DISABLED -> "管理员登录已经停用。";
            case ADMIN_FLOW_EXPIRED -> "管理员认证流程已过期，请重新开始。";
            case ADMIN_RATE_LIMITED -> "尝试次数过多，请稍后再试。";
            case HCAPTCHA_REJECTED -> "请重新完成人机验证。";
            case HCAPTCHA_UNAVAILABLE -> "人机验证服务暂时不可用。";
            case ADMIN_CREDENTIALS_INVALID -> "管理员凭证不正确。";
            case ADMIN_SESSION_INVALID -> "管理员登录状态无效或已过期。";
            case ADMIN_PREAUTH_REQUIRED -> "管理员 PreAuth 已失效，请重新初始化安全上下文。";
            case ADMIN_SESSION_LIMIT_REACHED -> "管理员登录设备已达到上限。";
            case ADMIN_PHONE_CHANNEL_INVALID -> "当前手机号不支持所选验证码渠道。";
            case ADMIN_IDENTITY_INVALID -> "邮箱、国家或手机号格式无效。";
            case ADMIN_VERIFICATION_INVALID -> "验证码不正确或已过期。";
            case ADMIN_PASSWORD_INVALID -> "密码强度不足或两次输入不一致。";
            case ADMIN_FLOW_INVALID -> "管理员认证流程无效，请重新开始。";
            default -> "管理员认证服务暂时不可用。";
        };
    }
}
