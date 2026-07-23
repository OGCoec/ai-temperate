package com.example.temperate.web.auth.csrf.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 为未登录 H5 页面初始化 CSRF Cookie 的接口控制器。
 *
 * <p>用途：返回无内容响应并触发 Spring 写入可由 JavaScript 读取的 {@code XSRF-TOKEN} Cookie；不签发登录凭据，
 * 不为 Android 提供会话保护。</p>
 */
@RestController
@RequestMapping("/api/auth/csrf")
@Tag(
        name = "认证-CSRF",
        description = "为 H5 单页应用初始化可读的 CSRF Cookie；不签发登录凭证，不负责 Android 会话保护。")
public final class CsrfTokenController {

    @GetMapping
    @Operation(
            summary = "初始化 H5 CSRF Cookie",
            description = "返回 204，并通过 Set-Cookie 写入 Secure、SameSite=Strict 的 XSRF-TOKEN 会话 Cookie。")
    public ResponseEntity<Void> csrf(CsrfToken csrfToken) {
        if (csrfToken == null) {
            throw new IllegalArgumentException("CSRF Cookie endpoint only supports H5 clients.");
        }
        // CsrfToken 可能是延迟对象，主动取值确保本次 204 响应携带初始化 Cookie。
        csrfToken.getToken();
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
