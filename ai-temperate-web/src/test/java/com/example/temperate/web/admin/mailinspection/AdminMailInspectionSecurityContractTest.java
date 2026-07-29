package com.example.temperate.web.admin.mailinspection;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.web.admin.security.AdminClientPlatformResolver;
import com.example.temperate.web.admin.security.AdminSecurityConfiguration;
import com.example.temperate.web.admin.security.AdminWebMvcConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 验证新增邮箱检查路径仍由管理员通配安全链覆盖，且没有进入公开路径或 CSRF 忽略列表。
 */
final class AdminMailInspectionSecurityContractTest {

    private static final List<String> ROUTES = List.of(
            "/api/admin/mail-inspection/openai-status-jobs",
            "/api/admin/mail-inspection/kiro-status-jobs",
            "/api/admin/mail-inspection/ip2location-registration-jobs",
            "/api/admin/mail-inspection/ip2location-verify-link-jobs",
            "/api/admin/mail-inspection/recovered-jobs",
            "/api/admin/mail-inspection/jobs/AAAAAAAAAAE/resume");

    private static final List<String> POST_ROUTES = List.of(
            "/api/admin/mail-inspection/openai-status-jobs",
            "/api/admin/mail-inspection/kiro-status-jobs",
            "/api/admin/mail-inspection/ip2location-registration-jobs",
            "/api/admin/mail-inspection/ip2location-verify-link-jobs",
            "/api/admin/mail-inspection/jobs/AAAAAAAAAAE/resume");

    @Test
    void routesAreNotAddedToPublicAdministratorPaths() throws Exception {
        Field publicPaths =
                AdminWebMvcConfiguration.class.getDeclaredField("PUBLIC_ADMIN_PATHS");
        publicPaths.setAccessible(true);
        List<String> values =
                Arrays.asList((String[]) publicPaths.get(null));

        assertThat(values).doesNotContainAnyElementsOf(ROUTES);

        Field protectedPaths =
                AdminWebMvcConfiguration.class.getDeclaredField("ADMIN_PATHS");
        protectedPaths.setAccessible(true);
        assertThat((String[]) protectedPaths.get(null))
                .contains("/api/admin/**");
    }

    @Test
    void h5PostRoutesStillRequireCsrf() throws Exception {
        AdminSecurityConfiguration configuration =
                new AdminSecurityConfiguration(new AdminClientPlatformResolver());
        Method ignoreCsrf = AdminSecurityConfiguration.class.getDeclaredMethod(
                "ignoreCsrf", jakarta.servlet.http.HttpServletRequest.class);
        ignoreCsrf.setAccessible(true);

        for (String route : POST_ROUTES) {
            MockHttpServletRequest request =
                    new MockHttpServletRequest("POST", route);
            request.addHeader("X-Client-Platform", "H5");
            assertThat((boolean) ignoreCsrf.invoke(configuration, request))
                    .as(route)
                    .isFalse();
        }
    }

    @Test
    void wildcardSecurityAndNetworkRiskChainsStillCoverNewRoutes()
            throws Exception {
        String adminSecurity = Files.readString(
                Path.of("src/main/java/com/example/temperate/web/admin/security/"
                        + "AdminSecurityConfiguration.java"),
                StandardCharsets.UTF_8);
        String networkRisk = Files.readString(
                Path.of("src/main/java/com/example/temperate/web/risk/"
                        + "NetworkRiskWebMvcConfiguration.java"),
                StandardCharsets.UTF_8);

        assertThat(adminSecurity)
                .contains(".securityMatcher(\"/api/admin/**\")")
                .contains("EdgeProxySignatureFilter");
        assertThat(networkRisk).contains(".addPathPatterns(\"/api/**\")");
        assertThat(adminSecurity)
                .doesNotContain("/api/admin/mail-inspection");
        assertThat(networkRisk)
                .doesNotContain("/api/admin/mail-inspection");
    }
}
