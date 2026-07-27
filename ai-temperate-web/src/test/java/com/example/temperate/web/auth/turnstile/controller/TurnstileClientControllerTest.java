package com.example.temperate.web.auth.turnstile.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.web.auth.config.properties.AuthSecurityProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * 验证 Android Turnstile 页面只在供应商 ready 回调后渲染，并保持有界重试与错误码净化边界。
 */
class TurnstileClientControllerTest {

    @Test
    void returnsSeparatedPageResourcesWithReadyCallbackAndBoundedRecovery()
            throws IOException {
        AuthSecurityProperties properties = mock(AuthSecurityProperties.class);
        when(properties.turnstile()).thenReturn(new AuthSecurityProperties.Turnstile(
                "1x00000000000000000000AA",
                "1x0000000000000000000000000000000AA",
                List.of("localhost")));
        TurnstileClientController controller = new TurnstileClientController(properties);

        var pageResponse = controller.page(
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKL", "register");
        var styleResponse = controller.pageStyle();
        var scriptResponse = controller.pageScript();
        Resource page = pageResponse.getBody();
        Resource style = styleResponse.getBody();
        Resource script = scriptResponse.getBody();

        assertThat(page).isInstanceOf(ClassPathResource.class);
        assertThat(style).isInstanceOf(ClassPathResource.class);
        assertThat(script).isInstanceOf(ClassPathResource.class);
        String html = read(page);
        String css = read(style);
        String javascript = read(script);
        assertThat(javascript.indexOf("window.aitTurnstileSdkReady=function"))
                .isLessThan(javascript.indexOf(
                        "api.js?render=explicit&onload=aitTurnstileSdkReady"));
        assertThat(html)
                .contains("./page.css", "./page.js")
                .doesNotContain("<style", "<script>")
                .doesNotContain("style=");
        assertThat(css)
                .contains(".shell", ".actions", "#retry");
        assertThat(javascript)
                .contains("/api/auth/turnstile/config")
                .contains("window.location.search")
                .doesNotContain("if(!window.turnstile){if(attempts++")
                .contains("MAX_AUTO_RETRIES=1")
                .contains("SDK_READY_TIMEOUT_MS=15000")
                .contains("retry:'never'")
                .contains("sanitizeCode")
                .contains("重新验证")
                .doesNotContain("0000000000000000000000000000000AA");
        assertThat(pageResponse.getHeaders().getCacheControl()).contains("no-store");
        assertThat(styleResponse.getHeaders().getCacheControl()).contains("no-store");
        assertThat(scriptResponse.getHeaders().getCacheControl()).contains("no-store");
        assertThat(styleResponse.getHeaders().getContentType().toString())
                .isEqualTo("text/css;charset=UTF-8");
        assertThat(scriptResponse.getHeaders().getContentType().toString())
                .isEqualTo("application/javascript;charset=UTF-8");
    }

    @Test
    void controllerSourceContainsNoFrontendImplementation() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/example/temperate/web/auth/turnstile/controller/TurnstileClientController.java"));

        assertThat(source)
                .doesNotContain("<html")
                .doesNotContain("<style")
                .doesNotContain("<script")
                .doesNotContain("challenges.cloudflare.com/turnstile/v0/api.js");
    }

    private static String read(Resource resource) throws IOException {
        try (var input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
