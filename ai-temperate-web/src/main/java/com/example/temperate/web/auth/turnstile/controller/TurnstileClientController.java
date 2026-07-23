package com.example.temperate.web.auth.turnstile.controller;

import com.example.temperate.web.auth.config.properties.AuthSecurityProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 为 H5 和 Android 受控 WebView 提供 Turnstile 客户端配置的接口控制器。
 *
 * <p>用途：仅公开可公开的 Site Key，并生成受限参数的第一方验证页面。</p>
 *
 * <p>安全原理：Secret Key 永不离开服务端；挑战句柄和动作在插入 HTML 前经过长度、字符集和白名单校验，页面使用
 * 无缓存响应与严格 CSP 限制脚本、框架和网络来源。</p>
 */
@RestController
@RequestMapping("/api/auth/turnstile")
@Tag(
        name = "认证-人机验证客户端",
        description = "向 H5 暴露非敏感的 Cloudflare Turnstile Site Key，并为 Android 受控 WebView 提供第一方 HTTPS 验证页面；Secret Key 永远不会发送给客户端。")
public final class TurnstileClientController {

    private static final Pattern CHALLENGE = Pattern.compile("^[A-Za-z0-9_-]{38}$");
    private static final Pattern ACTION = Pattern.compile("^[a-z][a-z0-9_-]{1,31}$");
    private static final Set<String> ALLOWED_ACTIONS =
            Set.of("register", "login", "password_reset");

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
    public ResponseEntity<String> page(
            @RequestParam String challenge,
            @RequestParam String action) {
        // 参数随后会进入内嵌页面；先做严格白名单校验，避免把任意请求参数拼接到 HTML/JavaScript 中。
        if (!CHALLENGE.matcher(challenge).matches()
                || !ACTION.matcher(action).matches()
                || !ALLOWED_ACTIONS.contains(action)) {
            throw new IllegalArgumentException("Turnstile flow parameters are invalid.");
        }
        String html = html(properties.turnstile().siteKey(), challenge, action);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    private static String html(String siteKey, String challenge, String action) {
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
                  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' https://challenges.cloudflare.com https://js.cdn.aliyun.dcloud.net.cn; frame-src https://challenges.cloudflare.com; connect-src https://challenges.cloudflare.com; style-src 'unsafe-inline'; img-src data: https://challenges.cloudflare.com">
                  <title>安全验证</title>
                  <style>html,body{height:100%%;margin:0;background:#0b0d0c;color:#f3f5f4;font-family:system-ui,sans-serif}.shell{min-height:100%%;display:flex;align-items:center;justify-content:center;padding:24px;box-sizing:border-box}.card{width:min(100%%,420px)}h1{font-size:22px;margin:0 0 8px}.hint{color:#98a29d;font-size:14px;margin:0 0 24px}#widget{min-height:70px}.error{color:#ff8e8e;font-size:14px;margin-top:16px}</style>
                  <script src="https://js.cdn.aliyun.dcloud.net.cn/dev/uni-app/uni.webview.1.5.6.js"></script>
                  <script src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit" defer></script>
                </head>
                <body>
                  <main class="shell"><section class="card"><h1>完成安全验证</h1><p class="hint">验证完成后将自动返回应用。</p><div id="widget"></div><p id="error" class="error" role="alert"></p></section></main>
                  <script>
                    window.addEventListener('load',function(){
                      var attempts=0;
                      function render(){
                        if(!window.turnstile){if(attempts++<50){setTimeout(render,100);return;}document.getElementById('error').textContent='验证组件加载失败，请检查网络后重试。';return;}
                        window.turnstile.render('#widget',{sitekey:'%s',action:'%s',cData:'%s',theme:'dark',callback:function(token){
                          if(window.uni&&window.uni.postMessage){window.uni.postMessage({data:{type:'turnstile',token:token}});}
                          window.location.href='aiturnstile://verified?token='+encodeURIComponent(token);
                        },'error-callback':function(){document.getElementById('error').textContent='验证失败，请重试。';}});
                      }
                      render();
                    });
                  </script>
                </body>
                </html>
                """.formatted(siteKey, action, challenge);
    }

    public record TurnstileConfigResponse(String siteKey) {
    }
}
