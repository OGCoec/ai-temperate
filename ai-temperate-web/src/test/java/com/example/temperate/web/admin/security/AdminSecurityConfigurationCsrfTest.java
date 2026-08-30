package com.example.temperate.web.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.admin.config.properties.AdminProperties;
import com.example.temperate.web.auth.config.SpaCsrfTokenRequestHandler;
import com.example.temperate.web.auth.diagnostic.filter.AuthRequestTraceFilter;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.web.cors.CorsConfiguration;

/**
 * 验证管理员公开读取请求会主动解析管理员 CSRF Token，并继续接受原始双提交请求头。
 *
 * <p>管理员链复用 SPA 请求处理器，但使用独立 Cookie 仓库；测试只固定签发时机和 Header
 * 解析协议，不允许管理员链依赖普通用户 {@code XSRF-TOKEN}。</p>
 */
class AdminSecurityConfigurationCsrfTest {

    @Test
    void resolvesAdministratorCsrfTokenForPublicGetRequests() {
        SpaCsrfTokenRequestHandler handler = new SpaCsrfTokenRequestHandler();

        for (String path : List.of(
                "/api/admin/auth/state",
                "/api/admin/auth/phone-country",
                "/api/admin/auth/hcaptcha/config")) {
            AtomicInteger resolutions = new AtomicInteger();
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);

            handler.handle(
                    request,
                    new MockHttpServletResponse(),
                    () -> {
                        resolutions.incrementAndGet();
                        return token();
                    });

            assertThat(resolutions)
                    .as("public administrator GET must initialize CSRF: %s", path)
                    .hasValue(1);
        }
    }

    @Test
    void resolvesTheRawAdministratorCsrfHeader() {
        SpaCsrfTokenRequestHandler handler = new SpaCsrfTokenRequestHandler();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Admin-CSRF-Token", "administrator-csrf");

        assertThat(handler.resolveCsrfTokenValue(request, token()))
                .isEqualTo("administrator-csrf");
    }

    @Test
    void allowsAllAdministratorMutationMethodsAtTheCorsBoundary() {
        AdminSecurityConfiguration configuration =
                new AdminSecurityConfiguration(new AdminClientPlatformResolver());
        CorsConfiguration cors = configuration.adminCorsConfigurationSource(
                        AdminProperties.testDefaults(Path.of("admin.yml")))
                .getCorsConfiguration(new MockHttpServletRequest(
                        "PATCH", "/api/admin/ai-model-icons/AAAAAAAAAAI"));

        assertThat(cors).isNotNull();
        assertThat(cors.getAllowedMethods())
                .containsExactly("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
        assertThat(cors.getAllowedHeaders())
                .contains(
                        AuthRequestTraceFilter.CLIENT_REQUEST_HEADER,
                        AuthRequestTraceFilter.PAGE_INSTANCE_HEADER,
                        AuthRequestTraceFilter.CLIENT_QUEUE_HEADER,
                        AuthRequestTraceFilter.WEBRTC_PROBE_RUN_HEADER);
        assertThat(cors.getExposedHeaders())
                .contains(AuthRequestTraceFilter.WEBRTC_PROBE_RUN_HEADER);
    }

    private static DefaultCsrfToken token() {
        return new DefaultCsrfToken(
                "X-Admin-CSRF-Token",
                "_csrf",
                "administrator-csrf");
    }
}
