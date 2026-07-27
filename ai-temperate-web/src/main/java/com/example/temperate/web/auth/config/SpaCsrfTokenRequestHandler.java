package com.example.temperate.web.auth.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

/**
 * 兼容单页应用 Cookie CSRF 传输的 Spring CSRF 请求处理器。
 *
 * <p>用途：促使 Spring 写入当前安全链 Cookie 仓库配置的延迟 CSRF Cookie，并在前端显式提交仓库配置的
 * Header 时按原始 Token 校验。普通用户链使用 {@code XSRF-TOKEN}，管理员链使用独立的
 * {@code ADMIN-XSRF-TOKEN}，两者不会共用仓库或请求头。</p>
 *
 * <p>安全原理：Header 存在时使用非 XOR 处理器匹配 Cookie 原始值；Header 缺失时保留 Spring 的 XOR 请求属性
 * 行为，避免混淆两个编码表示。</p>
 */
public final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            Supplier<CsrfToken> csrfToken) {
        xor.handle(request, response, csrfToken);
        csrfToken.get();
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String headerValue = request.getHeader(csrfToken.getHeaderName());
        // JavaScript 读取 Cookie 后提交的是原始 Token；仅无 Header 的内部属性走 XOR 表示。
        CsrfTokenRequestHandler delegate = StringUtils.hasText(headerValue) ? plain : xor;
        return delegate.resolveCsrfTokenValue(request, csrfToken);
    }
}
