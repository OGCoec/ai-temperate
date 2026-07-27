package com.example.temperate.web.risk.webrtc;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 为四个 WebRTC 边缘端点的成功与失败响应统一设置禁止缓存，防止完整失败 IP 被浏览器或代理保留。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class WebRtcNoStoreFilter extends OncePerRequestFilter {

    private static final Set<String> PATHS = Set.of(
            "/api/_edge/webrtc/start",
            "/api/_edge/webrtc/report",
            "/api/admin/_edge/webrtc/start",
            "/api/admin/_edge/webrtc/report");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATHS.contains(path(request));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        filterChain.doFilter(request, response);
    }

    private static String path(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String uri = request.getRequestURI();
        if (contextPath == null || contextPath.isEmpty()) {
            return uri;
        }
        return uri.startsWith(contextPath)
                ? uri.substring(contextPath.length())
                : uri;
    }
}
