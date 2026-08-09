package com.example.temperate.web.edgeproxy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 在 CORS、CSRF 和业务认证之前校验 Cloudflare Worker 的 API 与语音 WebSocket 浏览器请求签名。
 *
 * <p>REQUIRED 只强制带 Origin 的 H5 请求；没有 Origin 的现有 Android 原生协议保持直连。
 * 任意模式下，只要请求携带部分边缘头，就必须完整验签，避免伪造属性进入后续 Cookie
 * 作用域判断。</p>
 */
public final class EdgeProxySignatureFilter extends OncePerRequestFilter {

    private static final String ERROR_BODY =
            "{\"code\":\"EDGE_PROXY_SIGNATURE_INVALID\","
                    + "\"message\":\"Edge proxy signature is invalid.\"}";

    private final EdgeProxyProperties properties;
    private final EdgeProxySignatureVerifier verifier;

    /**
     * 创建只处理 API 与公开语音 WebSocket Upgrade 请求的边缘签名过滤器。
     *
     * @param properties 边缘代理模式配置
     * @param verifier 请求级签名验签器
     */
    public EdgeProxySignatureFilter(
            EdgeProxyProperties properties,
            EdgeProxySignatureVerifier verifier) {
        this.properties = Objects.requireNonNull(properties);
        this.verifier = Objects.requireNonNull(verifier);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // 只把公开语音握手的精确路径纳入边缘边界，避免未来新增的内部 /ws 路径被意外暴露或改变认证语义。
        return uri == null
                || !(uri.equals("/api")
                        || uri.startsWith("/api/")
                        || uri.equals("/ws/voice"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (properties.mode() == EdgeProxyMode.DISABLED) {
            filterChain.doFilter(request, response);
            return;
        }

        boolean hasEdgeHeader = verifier.hasAnyEdgeHeader(request);
        boolean browserRequest = hasText(request.getHeader("Origin"));
        if (!hasEdgeHeader) {
            if (properties.mode() == EdgeProxyMode.REQUIRED && browserRequest) {
                reject(response);
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        try {
            EdgeProxyVerificationResult result = verifier.verify(request);
            request.setAttribute(
                    TrustedExternalHostResolver.VERIFIED_EXTERNAL_HOST_ATTRIBUTE,
                    result.externalHost());
            result.optionalNetworkContext().ifPresent(context -> request.setAttribute(
                    TrustedEdgeNetworkContextResolver.VERIFIED_NETWORK_CONTEXT_ATTRIBUTE,
                    context));
            filterChain.doFilter(request, response);
        } catch (EdgeProxyVerificationException exception) {
            reject(response);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(ERROR_BODY);
    }
}
