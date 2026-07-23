package com.example.temperate.web.auth.interceptor;

import com.example.temperate.service.registration.dto.query.RegistrationStatusQuery;
import com.example.temperate.service.registration.enums.RegistrationDiagnosticCode;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.example.temperate.service.registration.flow.security.RegistrationAccess;
import com.example.temperate.service.registration.service.lifecycle.RegistrationService;
import com.example.temperate.web.auth.flow.transport.AuthFlowCookieWriter;
import com.example.temperate.web.auth.registration.controller.RegistrationController;
import com.example.temperate.web.auth.session.transport.AuthClientPlatform;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 注册流程受保护接口的统一前置校验拦截器。
 *
 * <p>用途：从请求头和网络连接信息构造注册访问材料，并在进入具体注册操作前验证流程、CSRF、挑战和设备绑定。</p>
 *
 * <p>安全边界：调用状态查询不会放宽流程权限；其职责是让无效、过期或不匹配的流程在 Controller 执行业务动作前
 * 被状态机拒绝。</p>
 */
@Component
public final class RegistrationFlowInterceptor implements HandlerInterceptor {

    private final RegistrationService registrationService;
    private final AuthFlowCookieWriter flowCookieWriter;

    public RegistrationFlowInterceptor(
            RegistrationService registrationService,
            AuthFlowCookieWriter flowCookieWriter) {
        this.registrationService = registrationService;
        this.flowCookieWriter = flowCookieWriter;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        RegistrationAccess access = access(request);
        try {
            registrationService.status(new RegistrationStatusQuery(access));
        } catch (RegistrationException exception) {
            // 流程拦截发生在 Controller 和 verifyTurnstile 之前，必须在这里保留失败阶段，否则绿色组件后的
            // Cookie、设备或挑战失配只能看到统一业务消息，无法与 Siteverify 拒绝区分。
            throw diagnosed(exception);
        }
        return true;
    }

    private RegistrationAccess access(HttpServletRequest request) {
        if (AuthClientPlatform.fromHeader(request.getHeader(RegistrationController.PLATFORM_HEADER))
                == AuthClientPlatform.H5) {
            AuthFlowCookieWriter.RegistrationFlowCookies cookies =
                    flowCookieWriter.registration(request);
            return new RegistrationAccess(
                    cookies.registerToken(),
                    cookies.flowCsrf(),
                    cookies.challengeHandle(),
                    request.getHeader(RegistrationController.DEVICE_HEADER),
                    request.getRemoteAddr());
        }
        return new RegistrationAccess(
                request.getHeader(RegistrationController.TOKEN_HEADER),
                request.getHeader(RegistrationController.FLOW_CSRF_HEADER),
                request.getHeader(RegistrationController.CHALLENGE_HEADER),
                request.getHeader(RegistrationController.DEVICE_HEADER),
                request.getRemoteAddr());
    }

    private static RegistrationException diagnosed(RegistrationException exception) {
        if (exception.diagnosticCode().isPresent()) {
            return exception;
        }
        RegistrationDiagnosticCode diagnosticCode = switch (exception.code()) {
            case REGISTRATION_FLOW_NOT_FOUND -> RegistrationDiagnosticCode.FLOW_NOT_FOUND;
            case REGISTRATION_FLOW_EXPIRED -> RegistrationDiagnosticCode.FLOW_EXPIRED;
            case REGISTRATION_FLOW_FORBIDDEN -> RegistrationDiagnosticCode.FLOW_ACCESS_REJECTED;
            default -> RegistrationDiagnosticCode.REDIS_FLOW_LOOKUP_FAILED;
        };
        return new RegistrationException(
                exception.code(),
                exception.getMessage(),
                diagnosticCode,
                exception);
    }
}
