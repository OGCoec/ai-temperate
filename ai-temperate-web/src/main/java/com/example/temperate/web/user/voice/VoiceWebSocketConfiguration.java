package com.example.temperate.web.user.voice;

import com.example.temperate.service.user.voice.config.VoiceProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 将公开语音 WSS 注册到现有 HTTPS 端口，使其复用 Spring Boot TLS 证书和部署入口。
 */
@Configuration
@EnableWebSocket
@ConditionalOnProperty(prefix = "app.voice", name = "enabled", havingValue = "true")
public class VoiceWebSocketConfiguration implements WebSocketConfigurer {

    private final VoiceWebSocketHandler handler;
    private final VoiceWebSocketOriginInterceptor originInterceptor;
    private final VoiceProperties properties;

    public VoiceWebSocketConfiguration(
            VoiceWebSocketHandler handler,
            VoiceWebSocketOriginInterceptor originInterceptor,
            VoiceProperties properties) {
        this.handler = handler;
        this.originInterceptor = originInterceptor;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, properties.publicPath())
                .addInterceptors(originInterceptor)
                .setAllowedOrigins(properties.allowedOrigins().toArray(String[]::new));
    }
}
