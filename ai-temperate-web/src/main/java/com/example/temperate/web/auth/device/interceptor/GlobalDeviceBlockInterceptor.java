package com.example.temperate.web.auth.device.interceptor;

import com.example.temperate.service.auth.device.exception.GlobalDeviceBlockInfrastructureException;
import com.example.temperate.service.auth.device.service.GlobalDeviceBlockService;
import com.example.temperate.web.auth.api.ApiErrorResponse;
import com.example.temperate.web.auth.device.exception.GlobalDeviceBlockException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 在 MVC Controller 执行前统一拦截命中全局设备封禁规则的认证入口与 Voice Ticket 签发请求。
 *
 * <p>该拦截器只读取设备安装标识并查询封禁状态，不创建封禁记录；具体封禁写入仍由登录、注册和找回密码各自的风控流程完成。
 * Spring 运行时使用完整构造器注入统一 ObjectMapper 和 Clock，测试便捷构造器只用于轻量单元测试。</p>
 */
@Component
public final class GlobalDeviceBlockInterceptor implements HandlerInterceptor {

    private static final String DEVICE_HEADER = "X-Device-Installation-Id";
    private static final String RETRY_AFTER = "Retry-After";

    private final GlobalDeviceBlockService blockService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    GlobalDeviceBlockInterceptor(GlobalDeviceBlockService blockService) {
        this(blockService, new ObjectMapper(), Clock.systemUTC());
    }

    /**
     * 明确声明 Spring 的注入构造器，避免多个构造器存在时回退到无参实例化路径。
     */
    @Autowired
    public GlobalDeviceBlockInterceptor(
            GlobalDeviceBlockService blockService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.blockService = Objects.requireNonNull(blockService);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) throws IOException {
        if (shouldSkip(request)) {
            return true;
        }
        try {
            Duration ttl = blockService.remainingBlockTtl(request.getHeader(DEVICE_HEADER));
            if (!ttl.isZero() && !ttl.isNegative()) {
                writeError(response, GlobalDeviceBlockException.blocked(), ttl);
                return false;
            }
            return true;
        } catch (IllegalArgumentException exception) {
            writeError(response, GlobalDeviceBlockException.invalidInput(), Duration.ZERO);
            return false;
        } catch (GlobalDeviceBlockException exception) {
            writeError(response, exception, Duration.ZERO);
            return false;
        } catch (GlobalDeviceBlockInfrastructureException exception) {
            writeError(response, GlobalDeviceBlockException.unavailable(), Duration.ZERO);
            return false;
        }
    }

    private static boolean shouldSkip(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = normalizedPath(request);
        // Provider 顶层授权与回调无法携带设备请求头，其安全绑定由 OAuth 一次性状态机完成。
        if (path.startsWith("/api/auth/oauth2/authorization/")
                || path.startsWith("/api/auth/oauth2/code/")) {
            return true;
        }
        return !isProtectedPath(path);
    }

    private static String normalizedPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            return path.substring(contextPath.length());
        }
        return path;
    }

    private static boolean isProtectedPath(String path) {
        if ("/api/auth/session/logout".equals(path)
                || "/api/auth/session/logout-all".equals(path)) {
            return false;
        }
        return matchesProtectedPrefix(path, "/api/auth/login")
                || matchesProtectedPrefix(path, "/api/auth/register")
                || matchesProtectedPrefix(path, "/api/auth/password-reset")
                || matchesProtectedPrefix(path, "/api/auth/oauth2")
                || "/api/auth/session/bootstrap".equals(path)
                || "/api/users/me/voice/session-tickets".equals(path);
    }

    private static boolean matchesProtectedPrefix(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private void writeError(
            HttpServletResponse response,
            GlobalDeviceBlockException exception,
            Duration retryAfter) throws IOException {
        response.setStatus(exception.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        if (!retryAfter.isZero() && !retryAfter.isNegative()) {
            response.setHeader(RETRY_AFTER,
                    Long.toString(Math.max(1L, retryAfter.toSeconds())));
        }
        objectMapper.writeValue(response.getWriter(), new ApiErrorResponse(
                exception.code(), exception.getMessage(), clock.instant()));
    }
}
