package com.example.temperate.web.user.voice;

import com.example.temperate.service.user.voice.VoiceClientPlatform;
import com.example.temperate.service.user.voice.config.VoiceProperties;
import com.example.temperate.service.user.voice.diagnostic.VoiceDiagnosticContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * 在 WebSocket Upgrade 前对 H5 与 Android 的 Origin、平台组合执行精确分类。
 *
 * <p>H5 必须命中 Origin 白名单，Android 必须不携带 Origin；该拦截器先于 Ticket 消费执行，
 * 防止非法来源消耗合法的一次性安全信封。</p>
 */
@Component
public final class VoiceWebSocketOriginInterceptor implements HandshakeInterceptor {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(VoiceWebSocketOriginInterceptor.class);
    public static final String ORIGIN_PRESENT_ATTRIBUTE =
            VoiceWebSocketOriginInterceptor.class.getName() + ".originPresent";
    public static final String PLATFORM_ATTRIBUTE =
            VoiceWebSocketOriginInterceptor.class.getName() + ".platform";
    private static final String PLATFORM_HEADER = "X-Client-Platform";

    private final Set<String> allowedOrigins;

    public VoiceWebSocketOriginInterceptor(VoiceProperties properties) {
        this.allowedOrigins = Set.copyOf(properties.allowedOrigins());
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        String origin = request.getHeaders().getOrigin();
        String platform = request.getHeaders().getFirst(PLATFORM_HEADER);
        if ("ANDROID".equals(platform) && (origin == null || origin.isBlank())) {
            attributes.put(ORIGIN_PRESENT_ATTRIBUTE, Boolean.FALSE);
            attributes.put(PLATFORM_ATTRIBUTE, VoiceClientPlatform.ANDROID);
            logClassification(
                    request, "ANDROID", false, true, -1, "ANDROID_ALLOWED");
            return true;
        }
        if ("H5".equals(platform) && allowedOrigins.contains(origin)) {
            attributes.put(ORIGIN_PRESENT_ATTRIBUTE, Boolean.TRUE);
            attributes.put(PLATFORM_ATTRIBUTE, VoiceClientPlatform.H5);
            logClassification(request, "H5", true, true, -1, "H5_ALLOWED");
            return true;
        }
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setCacheControl(CacheControl.noStore());
        logClassification(
                request,
                safePlatform(platform),
                origin != null && !origin.isBlank(),
                false,
                HttpStatus.FORBIDDEN.value(),
                "REJECTED");
        return false;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        // 握手后不保留 Origin 原文；安全主体由后续握手拦截器在返回 101 前完成核验。
    }

    private static void logClassification(
            ServerHttpRequest request,
            String platform,
            boolean originPresent,
            boolean allowed,
            int status,
            String outcome) {
        VoiceDiagnosticContext context = diagnosticContext(request);
        String template = "event=voice_ws_origin_classification traceId={} edgeRay={} "
                + "platform={} originPresent={} allowed={} status={} outcome={}";
        Object[] arguments = {
            context == null ? "ABSENT" : context.traceId(),
            context == null ? "ABSENT" : context.edgeRay(),
            platform,
            originPresent,
            allowed,
            status,
            outcome
        };
        try {
            if (allowed) {
                LOGGER.info(template, arguments);
            } else {
                LOGGER.warn(template, arguments);
            }
        } catch (RuntimeException ignored) {
            // 日志后端异常不能改变 Origin 与平台分类结果。
        }
    }

    private static VoiceDiagnosticContext diagnosticContext(ServerHttpRequest request) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return null;
        }
        HttpServletRequest rawRequest = servletRequest.getServletRequest();
        Object value = rawRequest.getAttribute(VoiceDiagnosticContext.ATTRIBUTE);
        return value instanceof VoiceDiagnosticContext context ? context : null;
    }

    private static String safePlatform(String value) {
        if (value == null || value.isBlank()) {
            return "ABSENT";
        }
        return "H5".equals(value) || "ANDROID".equals(value)
                ? value
                : "INVALID";
    }
}
