package com.example.temperate.web.admin.security;

import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.config.properties.AdminProperties;
import com.example.temperate.web.edgeproxy.TrustedExternalHostResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 用于校验管理员 H5 双提交 CSRF Cookie 能被已验签的浏览器外部 Host 读取。
 *
 * <p>该组件只比较受信任 Origin、API 主机和脱敏配置域，不读取或记录 Cookie、Flow Token、
 * Session 或 hCaptcha 响应；Android 使用显式 Header 协议，因此不受浏览器 Cookie Domain 约束。</p>
 */
@Component
public final class AdminH5CsrfCookieScopeValidator {

    private final AdminProperties properties;
    private final AdminClientPlatformResolver platformResolver;
    private final TrustedExternalHostResolver externalHostResolver;

    public AdminH5CsrfCookieScopeValidator(
            AdminProperties properties,
            AdminClientPlatformResolver platformResolver,
            TrustedExternalHostResolver externalHostResolver) {
        this.properties = Objects.requireNonNull(properties);
        this.platformResolver = Objects.requireNonNull(platformResolver);
        this.externalHostResolver = Objects.requireNonNull(externalHostResolver);
    }

    /**
     * 在创建或读取管理员 H5 Cookie 前验证 Origin 与可信外部 Host 拥有共同的可读作用域。
     *
     * <p>Worker 同源入口验签成功后允许 Host-only Cookie；迁移期直连 API 时仍要求显式父域。
     * Flow 边界失败时只清理注册和登录 Flow，避免不可读 Cookie 继续触发笼统错误，同时不破坏
     * 独立的管理员会话。</p>
     *
     * @param request 当前管理员请求
     */
    public void requireFlowReadable(HttpServletRequest request) {
        requireReadable(request, CleanupContext.FLOW);
    }

    /**
     * 在读取或刷新管理员 H5 会话前验证会话 CSRF Cookie 的共享作用域。
     *
     * <p>该边界失败时只终止管理员会话，不清理独立的注册或登录 Flow。</p>
     *
     * @param request 当前管理员会话请求
     */
    public void requireSessionReadable(HttpServletRequest request) {
        requireReadable(request, CleanupContext.SESSION);
    }

    private void requireReadable(
            HttpServletRequest request,
            CleanupContext cleanupContext) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(cleanupContext, "cleanupContext must not be null");
        if (platformResolver.isAndroid(request)) {
            return;
        }

        String frontendHost = trustedOriginHost(
                request.getHeader("Origin"), cleanupContext);
        String apiHost = normalizeHost(
                externalHostResolver.resolve(request)
                        .orElseGet(request::getServerName),
                cleanupContext);
        String csrfDomain = properties.cookies().csrfDomain();

        if (csrfDomain.isEmpty()) {
            if (frontendHost.equals(apiHost)) {
                return;
            }
            throw invalidConfiguration(cleanupContext);
        }
        if (!domainCovers(frontendHost, csrfDomain)
                || !domainCovers(apiHost, csrfDomain)) {
            throw invalidConfiguration(cleanupContext);
        }
    }

    private String trustedOriginHost(String origin, CleanupContext cleanupContext) {
        if (origin == null || origin.isBlank()
                || !properties.allowedOrigins().contains(origin)) {
            throw invalidConfiguration(cleanupContext);
        }
        try {
            URI uri = URI.create(origin);
            if (uri.getHost() == null
                    || uri.getUserInfo() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty())
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw invalidConfiguration(cleanupContext);
            }
            return normalizeHost(uri.getHost(), cleanupContext);
        } catch (IllegalArgumentException exception) {
            throw invalidConfiguration(cleanupContext);
        }
    }

    private static String normalizeHost(String host, CleanupContext cleanupContext) {
        if (host == null || host.isBlank()) {
            throw invalidConfiguration(cleanupContext);
        }
        return host.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean domainCovers(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static AdminException invalidConfiguration(CleanupContext cleanupContext) {
        return new AdminException(
                AdminErrorCode.ADMIN_CSRF_CONFIGURATION_INVALID,
                "Administrator H5 CSRF cookie scope is invalid.",
                null,
                cleanupContext.clearFlow,
                cleanupContext.clearSession);
    }

    private enum CleanupContext {
        FLOW(true, false),
        SESSION(false, true);

        private final boolean clearFlow;
        private final boolean clearSession;

        CleanupContext(boolean clearFlow, boolean clearSession) {
            this.clearFlow = clearFlow;
            this.clearSession = clearSession;
        }
    }
}
