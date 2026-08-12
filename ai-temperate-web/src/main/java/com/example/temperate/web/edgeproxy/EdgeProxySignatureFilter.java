package com.example.temperate.web.edgeproxy;

import com.example.temperate.service.user.voice.diagnostic.VoiceDiagnosticContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 在 CORS、CSRF 和业务认证之前校验 Cloudflare Worker 的 API 与语音 WebSocket 请求签名。
 *
 * <p>生产 REQUIRED 模式要求 H5 与 Android 都只能通过 Worker 进入受保护路径，不能依据
 * Origin 是否存在来放行原生客户端。OPTIONAL 仅用于切换期允许完全不带边缘头的请求；任意
 * 模式下只要携带部分边缘头，就必须完整验签，避免伪造属性进入后续认证与 Cookie 作用域判断。</p>
 */
public final class EdgeProxySignatureFilter extends OncePerRequestFilter {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(EdgeProxySignatureFilter.class);
    private static final String ERROR_BODY =
            "{\"code\":\"EDGE_PROXY_SIGNATURE_INVALID\","
                    + "\"message\":\"Edge proxy signature is invalid.\"}";

    private final EdgeProxyProperties properties;
    private final EdgeProxySignatureVerifier verifier;

    /**
     * 创建只处理 API 与公开语音 WebSocket Upgrade 请求的边缘签名过滤器。
     *
     * <p>过滤器只建立可信边缘边界，不负责 Android Token、H5 Cookie 或语音 Ticket 的业务认证。</p>
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
        boolean voiceRequest = "/ws/voice".equals(request.getRequestURI());
        if (properties.mode() == EdgeProxyMode.DISABLED) {
            logVoiceDecision(
                    request,
                    voiceRequest,
                    verifier.hasAnyEdgeHeader(request),
                    false,
                    "DISABLED");
            filterChain.doFilter(request, response);
            return;
        }

        boolean hasEdgeHeader = verifier.hasAnyEdgeHeader(request);
        if (!hasEdgeHeader) {
            // REQUIRED 不再把“无 Origin”视为原生客户端可信证明，否则攻击者可直接删除 Origin 绕过 Worker。
            if (properties.mode() == EdgeProxyMode.REQUIRED) {
                logVoiceDecision(
                        request, voiceRequest, false, false, "MISSING_REQUIRED");
                reject(response);
                return;
            }
            logVoiceDecision(
                    request, voiceRequest, false, false, "UNSIGNED_OPTIONAL");
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
            logVoiceDecision(request, voiceRequest, true, true, "VERIFIED");
            filterChain.doFilter(request, response);
        } catch (EdgeProxyVerificationException exception) {
            logVoiceDecision(request, voiceRequest, true, false, "INVALID");
            reject(response);
        }
    }

    private void logVoiceDecision(
            HttpServletRequest request,
            boolean voiceRequest,
            boolean edgeHeadersPresent,
            boolean edgeRayTrusted,
            String outcome) {
        if (!voiceRequest) {
            return;
        }
        Object value = request.getAttribute(VoiceDiagnosticContext.ATTRIBUTE);
        String traceId = value instanceof VoiceDiagnosticContext context
                ? context.traceId()
                : "ABSENT";
        String edgeRay = value instanceof VoiceDiagnosticContext context
                ? context.edgeRay()
                : "ABSENT";
        String template = "event=voice_ws_edge_signature traceId={} edgeRay={} "
                + "mode={} edgeHeadersPresent={} edgeRayTrusted={} outcome={}";
        try {
            if ("VERIFIED".equals(outcome)
                    || "DISABLED".equals(outcome)
                    || "UNSIGNED_OPTIONAL".equals(outcome)) {
                LOGGER.info(
                        template,
                        traceId,
                        edgeRay,
                        properties.mode().name(),
                        edgeHeadersPresent,
                        edgeRayTrusted,
                        outcome);
            } else {
                LOGGER.warn(
                        template,
                        traceId,
                        edgeRay,
                        properties.mode().name(),
                        edgeHeadersPresent,
                        edgeRayTrusted,
                        outcome);
            }
        } catch (RuntimeException ignored) {
            // 日志后端异常不能参与边缘签名放行或拒绝决策。
        }
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(ERROR_BODY);
    }
}
