package com.example.temperate.web.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.web.risk.NetworkRiskInterceptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;
import reactor.core.publisher.Mono;

/**
 * 验证管理员 Controller 保持独立路径并让 hCaptcha 登录完成接口返回异步 Mono。
 */
class AdminAuthControllerContractTest {

    @Test
    void exposesAdminOnlyBasePath() {
        RequestMapping mapping = AdminAuthController.class.getAnnotation(RequestMapping.class);

        assertThat(mapping.value()).containsExactly("/api/admin");
    }

    @Test
    void loginCompletionIsAsynchronous() {
        Method method = Arrays.stream(AdminAuthController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("completeLogin"))
                .findFirst()
                .orElseThrow();

        assertThat(method.getReturnType()).isEqualTo(Mono.class);
    }

    @Test
    void authenticatedPreAuthPromotionUsesOnlyTheVerifiedRequestAttribute()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/example/temperate/web/admin/controller/"
                        + "AdminAuthController.java"));

        assertThat(source)
                .contains("NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE")
                .contains("preAuthService.promoteAuthenticated(")
                .doesNotContain(
                        "promoteAuthenticated(\n                RiskScope.ADMIN,\n                rawPreAuth");
        assertThat(NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE).isNotBlank();
    }

    @Test
    void protectedEndpointsReadProfileAndRawTokenFromTheMvcSessionInterceptor()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/example/temperate/web/admin/controller/"
                        + "AdminAuthController.java"));

        assertThat(source)
                .contains(
                        "AdminSessionAuthenticationInterceptor.PROFILE_ATTRIBUTE",
                        "AdminSessionAuthenticationInterceptor.RAW_TOKEN_ATTRIBUTE")
                .doesNotContain("AdminSessionAuthenticationFilter");
    }
}
