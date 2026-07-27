package com.example.temperate.web.admin.security;

import com.example.temperate.web.auth.api.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 将管理员 H5 双提交 CSRF 失败转换为独立且不缓存的 JSON 错误，不清理普通用户 Cookie。
 */
@Component
public final class AdminCsrfAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AdminCsrfAccessDeniedHandler(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException, ServletException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(
                HttpHeaders.CACHE_CONTROL,
                CacheControl.noStore().cachePrivate().getHeaderValue());
        objectMapper.writeValue(
                response.getOutputStream(),
                new ApiErrorResponse(
                        "ADMIN_CSRF_INVALID",
                        "管理员请求安全校验失败。",
                        clock.instant()));
    }
}
