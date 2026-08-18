package com.example.temperate.web.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

/**
 * 该测试是来约束 Responses Trace 过滤器先于诊断过滤器运行，并确保诊断仅注册精确路径及 REQUEST/ASYNC/ERROR 分派。
 */
final class ApiResponsesDiagnosticFilterRegistrationTest {

    @Test
    void registersDiagnosticFilterImmediatelyAfterTraceFilter() {
        SecurityConfiguration configuration = new SecurityConfiguration();
        var trace = configuration.apiResponsesTraceFilterRegistration(
                configuration.apiResponsesTraceFilter());
        var diagnosticFilter = configuration.apiResponsesStreamDiagnosticFilter(
                new ApiKeyProperties());
        var diagnostic = configuration.apiResponsesStreamDiagnosticFilterRegistration(
                diagnosticFilter);

        assertThat(trace.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 11);
        assertThat(diagnostic.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 12);
        assertThat(diagnostic.getUrlPatterns()).containsExactly("/v1/responses");
        assertThat(diagnostic.determineDispatcherTypes()).containsExactlyInAnyOrder(
                DispatcherType.REQUEST,
                DispatcherType.ASYNC,
                DispatcherType.ERROR);
        assertThat(diagnostic.isAsyncSupported()).isTrue();
    }
}
