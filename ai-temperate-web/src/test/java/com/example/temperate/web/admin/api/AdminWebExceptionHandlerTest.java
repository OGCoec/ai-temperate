package com.example.temperate.web.admin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.config.properties.AdminProperties;
import com.example.temperate.web.admin.security.AdminClientPlatformResolver;
import com.example.temperate.web.admin.transport.AdminCookieWriter;
import com.example.temperate.web.auth.api.ApiErrorResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 验证管理员 Web 异常处理器把 CSRF Cookie 作用域错误转换成可诊断的 503 响应。
 *
 * <p>配置错误只清理当前 Flow 或会话上下文，避免注册恢复失败误删仍然有效的管理员会话。</p>
 */
class AdminWebExceptionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

    @Test
    void flowScopeFailureClearsFlowCookiesWithoutClearingSessionCookies() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AdminProperties properties = AdminProperties.testDefaults(
                Path.of("target/admin-web-exception-handler-test/complete.yaml"));
        AdminWebExceptionHandler handler = new AdminWebExceptionHandler(
                clock,
                new AdminCookieWriter(properties, clock),
                new AdminClientPlatformResolver(),
                mock(AdminExceptionLogger.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Origin", "https://admin.niko000o.site");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AdminException exception = new AdminException(
                AdminErrorCode.ADMIN_CSRF_CONFIGURATION_INVALID,
                "Administrator H5 CSRF cookie scope is invalid.",
                null,
                true,
                false);

        ResponseEntity<ApiErrorResponse> result = handler.handle(
                exception, request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().code())
                .isEqualTo(AdminErrorCode.ADMIN_CSRF_CONFIGURATION_INVALID.name());
        assertThat(result.getBody().message())
                .isEqualTo("管理员安全 Cookie 作用域配置无效，请联系管理员。");
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                .anyMatch(value -> value.startsWith("admin_register_token="))
                .anyMatch(value -> value.startsWith("admin_login_flow="))
                .noneMatch(value -> value.startsWith("admin_session="))
                .noneMatch(value -> value.startsWith("ADMIN-XSRF-TOKEN="));
    }

    @Test
    void sessionScopeFailureClearsSessionCookiesWithoutClearingFlowCookies() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AdminProperties properties = AdminProperties.testDefaults(
                Path.of("target/admin-web-exception-handler-test/complete.yaml"));
        AdminWebExceptionHandler handler = new AdminWebExceptionHandler(
                clock,
                new AdminCookieWriter(properties, clock),
                new AdminClientPlatformResolver(),
                mock(AdminExceptionLogger.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Origin", "https://admin.niko000o.site");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AdminException exception = new AdminException(
                AdminErrorCode.ADMIN_CSRF_CONFIGURATION_INVALID,
                "Administrator H5 CSRF cookie scope is invalid.",
                null,
                false,
                true);

        handler.handle(exception, request, response);

        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                .anyMatch(value -> value.startsWith("admin_session="))
                .anyMatch(value -> value.startsWith("ADMIN-XSRF-TOKEN="))
                .noneMatch(value -> value.startsWith("admin_register_token="))
                .noneMatch(value -> value.startsWith("admin_login_flow="));
    }
}
