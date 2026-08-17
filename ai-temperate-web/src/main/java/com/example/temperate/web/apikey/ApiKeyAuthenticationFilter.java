package com.example.temperate.web.apikey;

import com.example.temperate.service.user.apikey.authentication.ApiKeyAuthenticationException;
import com.example.temperate.service.user.apikey.authentication.ApiKeyAuthenticationInfrastructureException;
import com.example.temperate.service.user.apikey.authentication.ApiKeyAuthenticationService;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 该过滤器是来在 DispatcherServlet 读取请求体前完成固定 Bearer API Key 认证，并只把脱敏专用 Principal 写入无状态 SecurityContext。
 */
public final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final ApiKeyAuthenticationService authenticationService;
    private final ApiKeyProperties properties;
    private final OpenAiErrorResponseWriter errorWriter;

    public ApiKeyAuthenticationFilter(
            ApiKeyAuthenticationService authenticationService,
            ApiKeyProperties properties,
            OpenAiErrorResponseWriter errorWriter) {
        this.authenticationService = Objects.requireNonNull(authenticationService);
        this.properties = Objects.requireNonNull(properties);
        this.errorWriter = Objects.requireNonNull(errorWriter);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ApiKeyV1Paths.isApiKeyEndpoint(request.getMethod(), request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!properties.isEnabled()) {
            errorWriter.write(
                    response,
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "The API Key endpoint is not enabled.",
                    "server_error",
                    "api_key_endpoint_disabled");
            return;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null
                || !authorization.startsWith(PREFIX)
                || authorization.length() == PREFIX.length()
                || authorization.indexOf(',', PREFIX.length()) >= 0) {
            reject(response);
            return;
        }
        if (hasBrowserCredentialHeaders(request)) {
            errorWriter.write(
                    response,
                    HttpServletResponse.SC_FORBIDDEN,
                    "Browser requests are not allowed for long-lived API Keys.",
                    "permission_error",
                    "browser_request_not_allowed");
            return;
        }
        ApiKeyPrincipal principal;
        try {
            principal = authenticationService.authenticate(
                    authorization.substring(PREFIX.length()));
        } catch (ApiKeyAuthenticationException exception) {
            SecurityContextHolder.clearContext();
            reject(response);
            return;
        } catch (ApiKeyAuthenticationInfrastructureException exception) {
            SecurityContextHolder.clearContext();
            errorWriter.write(
                    response,
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "API Key authentication is temporarily unavailable.",
                    "server_error",
                    "api_key_authentication_unavailable");
            return;
        } catch (RuntimeException exception) {
            // 认证链的意外运行时故障同样按基础设施失败关闭，禁止落入容器默认错误页或泄露内部异常。
            SecurityContextHolder.clearContext();
            errorWriter.write(
                    response,
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "API Key authentication is temporarily unavailable.",
                    "server_error",
                    "api_key_authentication_unavailable");
            return;
        }
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        errorWriter.write(
                response,
                HttpServletResponse.SC_UNAUTHORIZED,
                "Invalid API Key.",
                "authentication_error",
                "invalid_api_key");
    }

    /**
     * 仅识别会把长期 API Key 带入浏览器凭据上下文的请求头。
     * Fetch Metadata 可能由服务端 Node Fetch 自动附加，不能用于判定客户端身份或可信度。
     */
    private static boolean hasBrowserCredentialHeaders(HttpServletRequest request) {
        return request.getHeader("Cookie") != null
                || request.getHeader("Origin") != null
                || request.getHeader("Referer") != null;
    }
}
