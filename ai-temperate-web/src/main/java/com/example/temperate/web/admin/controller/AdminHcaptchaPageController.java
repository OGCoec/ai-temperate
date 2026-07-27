package com.example.temperate.web.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 为 Android 受控 WebView 返回独立维护的管理员 hCaptcha 页面资源。
 *
 * <p>该控制器只负责页面路由和无缓存响应，不注入 Site Key、不保存 Token，也不参与管理员认证流程。</p>
 */
@RestController
@RequestMapping("/api/admin/auth/hcaptcha")
@Tag(
        name = "管理员-人机验证页面",
        description = "仅供 Android 受控 WebView 返回独立的管理员 hCaptcha 页面资源；不负责校验 Token 或签发管理员会话。")
public final class AdminHcaptchaPageController {

    private static final Resource PAGE =
            new ClassPathResource("verification-pages/admin-hcaptcha-page.html");
    private static final Resource PAGE_STYLE =
            new ClassPathResource("verification-pages/admin-hcaptcha-page.css");
    private static final Resource PAGE_SCRIPT =
            new ClassPathResource("verification-pages/admin-hcaptcha-page.js");
    private static final MediaType CSS =
            MediaType.parseMediaType("text/css;charset=UTF-8");
    private static final MediaType JAVASCRIPT =
            MediaType.parseMediaType("application/javascript;charset=UTF-8");

    @GetMapping(value = "/page", produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "展示 Android 管理员 hCaptcha 受控页面")
    public ResponseEntity<Resource> page() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .contentType(MediaType.TEXT_HTML)
                .body(PAGE);
    }

    @GetMapping(value = "/page.css", produces = "text/css;charset=UTF-8")
    @Operation(summary = "加载 Android 管理员 hCaptcha 页面样式资源")
    public ResponseEntity<Resource> pageStyle() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .contentType(CSS)
                .body(PAGE_STYLE);
    }

    @GetMapping(
            value = "/page.js",
            produces = "application/javascript;charset=UTF-8")
    @Operation(summary = "加载 Android 管理员 hCaptcha 页面脚本资源")
    public ResponseEntity<Resource> pageScript() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .contentType(JAVASCRIPT)
                .body(PAGE_SCRIPT);
    }
}
