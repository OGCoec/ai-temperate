package com.example.temperate.web.edgeproxy;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 向 Cookie 和 Origin 安全组件暴露已经过边缘 HMAC 校验的浏览器外部 Host。
 *
 * <p>该解析器只读取过滤器写入的请求属性，绝不读取 Forwarded、X-Forwarded-Host 或客户端
 * 自报头，因此不能单独用于绕过边缘验签。</p>
 */
@Component
public final class TrustedExternalHostResolver {

    public static final String VERIFIED_EXTERNAL_HOST_ATTRIBUTE =
            TrustedExternalHostResolver.class.getName() + ".verifiedExternalHost";

    /**
     * 返回当前请求已验签的外部 Host；直连或未验签请求返回空。
     *
     * @param request 当前请求
     * @return 已验签外部 Host
     */
    public Optional<String> resolve(HttpServletRequest request) {
        Object value = request.getAttribute(VERIFIED_EXTERNAL_HOST_ATTRIBUTE);
        return value instanceof String host && !host.isBlank()
                ? Optional.of(host)
                : Optional.empty();
    }
}
