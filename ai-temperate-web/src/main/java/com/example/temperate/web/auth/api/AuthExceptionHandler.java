package com.example.temperate.web.auth.api;

import com.example.temperate.service.auth.login.exception.LoginException;
import com.example.temperate.service.auth.passwordreset.PasswordResetException;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.service.registration.exception.RegistrationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

/**
 * 认证相关异常到统一 HTTP 错误响应的映射接口。
 *
 * <p>用途：约束注册、登录、会话、密码重置和基础设施异常使用一致的外部错误结构。</p>
 */
public interface AuthExceptionHandler {

    ResponseEntity<ApiErrorResponse> handleRegistration(
            RegistrationException exception,
            HttpServletRequest request,
            HttpServletResponse response);

    ResponseEntity<ApiErrorResponse> handleLogin(LoginException exception);

    ResponseEntity<ApiErrorResponse> handleSession(
            SessionAuthenticationException exception,
            HttpServletRequest request,
            HttpServletResponse response);

    ResponseEntity<ApiErrorResponse> handlePasswordReset(
            PasswordResetException exception,
            HttpServletRequest request,
            HttpServletResponse response);

    ResponseEntity<ApiErrorResponse> handleUnreadableMessage(
            HttpMessageNotReadableException exception);

    ResponseEntity<ApiErrorResponse> handleInvalidInput(Exception exception);

    ResponseEntity<ApiErrorResponse> handleDataIntegrity(DataIntegrityViolationException exception);

    ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception);
}
