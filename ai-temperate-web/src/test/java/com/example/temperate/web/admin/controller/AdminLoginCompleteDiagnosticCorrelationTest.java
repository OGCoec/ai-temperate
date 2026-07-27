package com.example.temperate.web.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 锁定管理员异步登录在 PreAuth 轮换回调中恢复原请求诊断上下文，避免 Reactor 线程切换丢失 traceId。
 */
class AdminLoginCompleteDiagnosticCorrelationTest {

    @Test
    void restoresNetworkRiskDiagnosticContextAroundReactivePromotion() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/example/temperate/web/admin/controller/"
                        + "AdminAuthController.java"));

        assertThat(source)
                .contains(
                        "NetworkRiskDiagnosticContext.snapshot(",
                        "NetworkRiskDiagnosticContext.call(",
                        "AuthRequestTraceFilter.TRACE_ATTRIBUTE",
                        "admin_login_promotion")
                .doesNotContain("MDC.put(");
    }
}
