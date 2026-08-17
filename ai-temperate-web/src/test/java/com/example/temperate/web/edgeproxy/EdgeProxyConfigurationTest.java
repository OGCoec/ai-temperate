package com.example.temperate.web.edgeproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * 验证生产环境未启用 REQUIRED 边缘签名模式时会产生明确的高等级配置错误。
 */
class EdgeProxyConfigurationTest {

    @Test
    void productionCompatibilityModeEmitsStableErrorWithoutLeakingSecret() throws Exception {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("app.security.env", "PROD"))
                .thenReturn("PROD");
        EdgeProxyProperties edge = new EdgeProxyProperties(
                EdgeProxyMode.OPTIONAL,
                "dGVzdC1vbmx5LWVkZ2Utc2VjcmV0LTAxMjM0NTY3ODkwYWJjZGVm",
                Duration.ofSeconds(30));
        Logger logger = (Logger) LoggerFactory.getLogger(EdgeProxyConfiguration.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new EdgeProxyConfiguration()
                    .edgeProxyProductionModeAudit(edge, environment)
                    .afterPropertiesSet();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage())
                    .isEqualTo(
                            "security_edge_proxy_mode_not_required "
                                    + "environment=PROD mode=OPTIONAL")
                    .doesNotContain(edge.hmacSecretBase64());
        });
    }

    @Test
    void requiredProductionModeDoesNotEmitConfigurationError() throws Exception {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("app.security.env", "PROD"))
                .thenReturn("PROD");
        EdgeProxyProperties edge = new EdgeProxyProperties(
                EdgeProxyMode.REQUIRED,
                "dGVzdC1vbmx5LWVkZ2Utc2VjcmV0LTAxMjM0NTY3ODkwYWJjZGVm",
                Duration.ofSeconds(30));
        Logger logger = (Logger) LoggerFactory.getLogger(EdgeProxyConfiguration.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new EdgeProxyConfiguration()
                    .edgeProxyProductionModeAudit(edge, environment)
                    .afterPropertiesSet();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).isEmpty();
    }
}
