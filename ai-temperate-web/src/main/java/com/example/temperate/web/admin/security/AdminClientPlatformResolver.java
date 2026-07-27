package com.example.temperate.web.admin.security;

import com.example.temperate.web.auth.session.transport.AuthClientPlatform;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 在管理员边界集中区分浏览器 Cookie 协议和 Android 原生 Header 协议。
 *
 * <p>客户端平台头本身不可信；浏览器脚本无法移除自动生成的 Origin，因此带 Origin 的请求始终按 H5
 * 处理，避免伪造 Android 头后绕过 CSRF 或让原始管理员 Token 进入 JSON。</p>
 */
@Component
public final class AdminClientPlatformResolver {

    private static final String PLATFORM_HEADER = "X-Client-Platform";

    public AuthClientPlatform resolve(HttpServletRequest request) {
        String declared = request.getHeader(PLATFORM_HEADER);
        String origin = request.getHeader("Origin");
        return "ANDROID".equalsIgnoreCase(declared)
                        && (origin == null || origin.isBlank())
                ? AuthClientPlatform.ANDROID
                : AuthClientPlatform.H5;
    }

    public boolean isAndroid(HttpServletRequest request) {
        return resolve(request) == AuthClientPlatform.ANDROID;
    }
}
