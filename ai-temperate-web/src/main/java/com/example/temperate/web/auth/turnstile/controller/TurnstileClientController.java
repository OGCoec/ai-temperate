package com.example.temperate.web.auth.turnstile.controller;

import com.example.temperate.web.auth.api.WebInvalidInputException;
import com.example.temperate.web.auth.config.properties.AuthSecurityProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 为 H5 公开 Turnstile Site Key，并为 Android 返回独立维护的验证页面资源。
 *
 * <p>该控制器只负责公开配置、参数白名单和 HTTP 缓存边界；页面结构、样式和客户端状态机全部位于静态资源中。</p>
 */
@RestController
@RequestMapping("/api/auth/turnstile")
@Tag(
        name = "认证-人机验证客户端",
        description = "向 H5 暴露非敏感的 Cloudflare Turnstile Site Key，并为 Android 受控 WebView 返回独立静态验证页面；Secret Key 永远不会发送给客户端。")
public final class TurnstileClientController {

    private static final Pattern CHALLENGE = Pattern.compile("^[A-Za-z0-9_-]{38}$");
    private static final Pattern ACTION = Pattern.compile("^[a-z][a-z0-9_-]{1,31}$");
    private static final Set<String> ALLOWED_ACTIONS =
            Set.of("register", "login", "password_reset", "oauth_phone");
    private static final Resource PAGE =
            new ClassPathResource("verification-pages/turnstile-page.html");
    private static final Resource PAGE_STYLE =
            new ClassPathResource("verification-pages/turnstile-page.css");
    private static final Resource PAGE_SCRIPT =
            new ClassPathResource("verification-pages/turnstile-page.js");
    private static final MediaType CSS =
            MediaType.parseMediaType("text/css;charset=UTF-8");
    private static final MediaType JAVASCRIPT =
            MediaType.parseMediaType("application/javascript;charset=UTF-8");

    private final AuthSecurityProperties properties;

    public TurnstileClientController(AuthSecurityProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/config")
    @Operation(summary = "获取客户端可公开的 Turnstile Site Key")
    public TurnstileConfigResponse config() {
        return new TurnstileConfigResponse(properties.turnstile().siteKey());
    }

    @GetMapping(value = "/page", produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "打开 Android 第一方 Turnstile 验证页面")
    public ResponseEntity<Resource> page(
            @RequestParam String challenge,
            @RequestParam String action) {
        // Query 会由静态页面读取；服务端必须先完成同一套白名单校验，不能把安全边界下放给 WebView。
        if (!CHALLENGE.matcher(challenge).matches()
                || !ACTION.matcher(action).matches()
                || !ALLOWED_ACTIONS.contains(action)) {
            throw new WebInvalidInputException();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_HTML)
                .body(PAGE);
    }

    @GetMapping(value = "/page.css", produces = "text/css;charset=UTF-8")
    @Operation(summary = "加载 Android Turnstile 页面样式资源")
    public ResponseEntity<Resource> pageStyle() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(CSS)
                .body(PAGE_STYLE);
    }

    @GetMapping(
            value = "/page.js",
            produces = "application/javascript;charset=UTF-8")
    @Operation(summary = "加载 Android Turnstile 页面脚本资源")
    public ResponseEntity<Resource> pageScript() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(JAVASCRIPT)
                .body(PAGE_SCRIPT);
    }

    /**
     * 表示允许公开给客户端的 Turnstile 配置，仅包含 Site Key，不包含服务端 Secret。
     */
    public record TurnstileConfigResponse(String siteKey) {
    }
}
