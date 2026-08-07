package com.example.temperate.web.user.voice;

import com.example.temperate.service.user.voice.config.VoiceProperties;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * 在 WebSocket Upgrade 前对白名单 H5 Origin 做精确匹配。
 *
 * <p>Android 原生 Socket 可以没有 Origin，但仍必须在五秒内提交经 HTTP 认证签发的一次性票据。</p>
 */
@Component
public final class VoiceWebSocketOriginInterceptor implements HandshakeInterceptor {

    public static final String ORIGIN_PRESENT_ATTRIBUTE =
            VoiceWebSocketOriginInterceptor.class.getName() + ".originPresent";

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
        if (origin == null || origin.isBlank()) {
            attributes.put(ORIGIN_PRESENT_ATTRIBUTE, Boolean.FALSE);
            return true;
        }
        if (allowedOrigins.contains(origin)) {
            attributes.put(ORIGIN_PRESENT_ATTRIBUTE, Boolean.TRUE);
            return true;
        }
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return false;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        // 握手后不保留请求材料；所有身份信息都来自随后原子消费的一次性票据。
    }
}
