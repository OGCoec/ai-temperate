package com.example.temperate.web.risk.webrtc;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 验证 WebRTC 闭环和管理员业务门只豁免精确公开路径，并按风险、WebRTC、配置、会话的顺序执行。
 *
 * <p>该契约同时防止管理员业务门退回 Servlet Filter，确保 Edge 签名仍保留在 Security Filter Chain。</p>
 */
class WebRtcSecurityPathContractTest {

    @Test
    void userAndAdminReportHaveExactCsrfExemptions() throws Exception {
        String user = source(
                "src/main/java/com/example/temperate/web/auth/config/",
                "SecurityConfiguration.java");
        String admin = source(
                "src/main/java/com/example/temperate/web/admin/security/",
                "AdminSecurityConfiguration.java");

        assertThat(user)
                .contains("WEBRTC_REPORT_PATH", "HttpMethod.POST.matches")
                .doesNotContain("/api/_edge/webrtc/**");
        assertThat(admin)
                .contains("path.equals(\"/api/admin/_edge/webrtc/report\")")
                .doesNotContain("/api/admin/_edge/webrtc/**");
    }

    @Test
    void administratorBusinessGatesAreMvcInterceptorsWithFixedOrderAndPaths()
            throws Exception {
        String mvc = source(
                "src/main/java/com/example/temperate/web/admin/security/",
                "AdminWebMvcConfiguration.java");
        String stateInterceptor = source(
                "src/main/java/com/example/temperate/web/admin/security/",
                "AdminConfigurationStateInterceptor.java");
        String sessionInterceptor = source(
                "src/main/java/com/example/temperate/web/admin/security/",
                "AdminSessionAuthenticationInterceptor.java");
        String security = source(
                "src/main/java/com/example/temperate/web/admin/security/",
                "AdminSecurityConfiguration.java");
        String edgeFilter = source(
                "src/main/java/com/example/temperate/web/edgeproxy/",
                "EdgeProxySignatureFilter.java");
        String networkRisk = source(
                "src/main/java/com/example/temperate/web/risk/",
                "NetworkRiskInterceptor.java");
        String webRtc = source(
                "src/main/java/com/example/temperate/web/risk/webrtc/",
                "WebRtcVerificationInterceptor.java");

        assertThat(mvc)
                .contains(
                        ".order(Ordered.HIGHEST_PRECEDENCE + 2)",
                        ".order(Ordered.HIGHEST_PRECEDENCE + 3)",
                        "\"/api/admin\"",
                        "\"/api/admin/**\"",
                        "\"/api/admin/auth/register\"",
                        "\"/api/admin/auth/register/**\"",
                        "\"/api/admin/auth/login\"",
                        "\"/api/admin/auth/login/**\"")
                .contains(
                        "/api/admin/_edge/webrtc/start",
                        "/api/admin/_edge/webrtc/report",
                        "/api/admin/auth/hcaptcha/page.css",
                        "/api/admin/auth/hcaptcha/page.js")
                .doesNotContain("/api/admin/auth/hcaptcha/**");
        assertThat(stateInterceptor)
                .contains("implements HandlerInterceptor")
                .contains(
                        "/api/admin/_edge/webrtc/start",
                        "/api/admin/_edge/webrtc/report",
                        "/api/admin/auth/hcaptcha/page.css",
                        "/api/admin/auth/hcaptcha/page.js")
                .doesNotContain("OncePerRequestFilter");
        assertThat(sessionInterceptor)
                .contains(
                        "implements HandlerInterceptor",
                        "NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE",
                        "AdminErrorCode.ADMIN_SESSION_INVALID",
                        "AdminErrorCode.ADMIN_PREAUTH_REQUIRED",
                        "SecurityContextHolder.clearContext()")
                .contains(
                        "/api/admin/_edge/webrtc/start",
                        "/api/admin/_edge/webrtc/report",
                        "/api/admin/auth/hcaptcha/page.css",
                        "/api/admin/auth/hcaptcha/page.js")
                .doesNotContain(
                        "OncePerRequestFilter",
                        "HandlerExceptionResolver");
        assertThat(security)
                .contains(
                        "EdgeProxySignatureFilter",
                        ".addFilterBefore(",
                        "CorsFilter.class")
                .doesNotContain(
                        "FilterRegistrationBean",
                        "AdminConfigurationStateFilter",
                        "AdminSessionAuthenticationFilter",
                        "AdminConfigurationStateInterceptor",
                        "AdminSessionAuthenticationInterceptor",
                        ".addFilterAfter(");
        assertThat(edgeFilter).contains("extends OncePerRequestFilter");
        assertThat(networkRisk).contains("implements HandlerInterceptor");
        assertThat(webRtc).contains("implements HandlerInterceptor");
        assertThat(Files.exists(Path.of(
                "src/main/java/com/example/temperate/web/admin/security/"
                        + "AdminConfigurationStateFilter.java")))
                .isFalse();
        assertThat(Files.exists(Path.of(
                "src/main/java/com/example/temperate/web/admin/security/"
                        + "AdminSessionAuthenticationFilter.java")))
                .isFalse();
    }

    @Test
    void networkRiskDoesNotExcludeWebRtcWhileWebRtcInterceptorExcludesItself()
            throws Exception {
        String network = source(
                "src/main/java/com/example/temperate/web/risk/",
                "NetworkRiskWebMvcConfiguration.java");
        String webRtc = source(
                "src/main/java/com/example/temperate/web/risk/webrtc/",
                "WebRtcWebMvcConfiguration.java");

        assertThat(network).doesNotContain("/webrtc/");
        assertThat(network).contains(
                "/api/auth/turnstile/page.css",
                "/api/auth/turnstile/page.js",
                "/api/admin/auth/hcaptcha/page.css",
                "/api/admin/auth/hcaptcha/page.js");
        assertThat(webRtc)
                .contains("Ordered.HIGHEST_PRECEDENCE + 1")
                .contains(
                        "/api/_edge/webrtc/start",
                        "/api/_edge/webrtc/report",
                        "/api/admin/_edge/webrtc/start",
                        "/api/admin/_edge/webrtc/report",
                        "/api/auth/turnstile/page.css",
                        "/api/auth/turnstile/page.js",
                        "/api/admin/auth/hcaptcha/page.css",
                        "/api/admin/auth/hcaptcha/page.js")
                .doesNotContain("/api/auth/turnstile/**")
                .doesNotContain("/api/admin/auth/hcaptcha/**");
    }

    private static String source(String directory, String name) throws Exception {
        return Files.readString(Path.of(directory + name));
    }
}
