package com.example.temperate.web.auth.config;

import com.example.temperate.web.auth.api.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 将 Spring Security CSRF 拒绝结果转换为统一 JSON 错误响应的处理器。
 *
 * <p>用途：为 H5 写请求的 CSRF 失败返回稳定的 403 与 {@code CSRF_INVALID} 错误码。</p>
 *
 * <p>安全原理：响应不回显 Cookie、Header 或实际 CSRF 值，避免错误处理链路泄露可用于伪造请求的材料。</p>
 */
@Component
public final class JsonCsrfAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JsonCsrfAccessDeniedHandler(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception) throws IOException, ServletException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                new ApiErrorResponse(
                        "CSRF_INVALID", "CSRF token is invalid.", clock.instant()));
    }
}
