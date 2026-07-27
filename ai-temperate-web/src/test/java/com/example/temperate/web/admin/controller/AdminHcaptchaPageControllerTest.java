package com.example.temperate.web.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * 验证 Android hCaptcha 页面以官方 ready 回调启动，并且不会泄露 Secret 或无限自动重试。
 */
class AdminHcaptchaPageControllerTest {

    @Test
    void returnsSeparatedPageResourcesWithReadyCallbackAndSanitizedBoundedErrors()
            throws IOException {
        AdminHcaptchaPageController controller = new AdminHcaptchaPageController();

        var pageResponse = controller.page();
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
        assertThat(javascript.indexOf("window.aitHcaptchaSdkReady=function"))
                .isLessThan(javascript.indexOf(
                        "api.js?render=explicit&recaptchacompat=off&onload=aitHcaptchaSdkReady"));
        assertThat(html)
                .contains("./page.css", "./page.js")
                .doesNotContain("<style", "<script>")
                .doesNotContain("style=");
        assertThat(css)
                .contains("main", ".actions", "#retry");
        assertThat(javascript)
                .contains("window.location.hash")
                .doesNotContain("if(!window.hcaptcha){if(attempts++")
                .contains("MAX_AUTO_RETRIES=1")
                .contains("SDK_READY_TIMEOUT_MS=15000")
                .contains("sanitizeCode")
                .contains("重新验证")
                .doesNotContain("secretKey");
        assertThat(pageResponse.getHeaders().getCacheControl())
                .contains("no-store", "private");
        assertThat(styleResponse.getHeaders().getCacheControl())
                .contains("no-store", "private");
        assertThat(scriptResponse.getHeaders().getCacheControl())
                .contains("no-store", "private");
        assertThat(styleResponse.getHeaders().getContentType().toString())
                .isEqualTo("text/css;charset=UTF-8");
        assertThat(scriptResponse.getHeaders().getContentType().toString())
                .isEqualTo("application/javascript;charset=UTF-8");
    }

    @Test
    void controllerSourceContainsNoFrontendImplementation() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/example/temperate/web/admin/controller/AdminHcaptchaPageController.java"));

        assertThat(source)
                .doesNotContain("<html")
                .doesNotContain("<style")
                .doesNotContain("<script")
                .doesNotContain("js.hcaptcha.com/1/api.js");
    }

    private static String read(Resource resource) throws IOException {
        try (var input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
