package com.example.temperate.web.admin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.temperate.service.admin.aimodel.discovery.exception.CliProxyModelDiscoveryErrorCode;
import com.example.temperate.service.admin.aimodel.discovery.exception.CliProxyModelDiscoveryException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * 验证 CLIProxyAPI 发现错误使用约定状态码，并且错误体不回显上游敏感信息。
 */
final class AdminCliProxyModelDiscoveryExceptionHandlerTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-29T15:30:00Z"),
            ZoneOffset.UTC);

    @Test
    void mapsDisabledUnavailableTimeoutAndBadGatewayFailures() {
        assertThat(AdminCliProxyModelDiscoveryExceptionHandler.class
                .getAnnotation(Order.class)
                .value())
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        AdminCliProxyModelDiscoveryExceptionHandler handler =
                new AdminCliProxyModelDiscoveryExceptionHandler(
                        CLOCK,
                        mock(AdminExceptionLogger.class));

        assertThat(status(handler, CliProxyModelDiscoveryErrorCode
                .CLI_PROXY_MODEL_DISCOVERY_DISABLED)).isEqualTo(503);
        assertThat(status(handler, CliProxyModelDiscoveryErrorCode
                .CLI_PROXY_UNAVAILABLE)).isEqualTo(503);
        assertThat(status(handler, CliProxyModelDiscoveryErrorCode
                .CLI_PROXY_TIMEOUT)).isEqualTo(504);
        assertThat(status(handler, CliProxyModelDiscoveryErrorCode
                .CLI_PROXY_AUTH_FAILED)).isEqualTo(502);
        assertThat(status(handler, CliProxyModelDiscoveryErrorCode
                .CLI_PROXY_REQUEST_FAILED)).isEqualTo(502);
        assertThat(status(handler, CliProxyModelDiscoveryErrorCode
                .CLI_PROXY_RESPONSE_INVALID)).isEqualTo(502);
    }

    @Test
    void returnsStableMessageWithoutExceptionDetails() {
        AdminCliProxyModelDiscoveryExceptionHandler handler =
                new AdminCliProxyModelDiscoveryExceptionHandler(
                        CLOCK,
                        mock(AdminExceptionLogger.class));
        var response = handler.handle(new CliProxyModelDiscoveryException(
                CliProxyModelDiscoveryErrorCode.CLI_PROXY_AUTH_FAILED,
                "secret-key sensitive-upstream-body"));

        assertThat(response.getHeaders().getCacheControl())
                .contains("private")
                .contains("no-store");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .doesNotContain("secret-key")
                .doesNotContain("sensitive-upstream-body");
    }

    private static int status(
            AdminCliProxyModelDiscoveryExceptionHandler handler,
            CliProxyModelDiscoveryErrorCode code) {
        return handler.handle(new CliProxyModelDiscoveryException(code, "internal"))
                .getStatusCode()
                .value();
    }
}
