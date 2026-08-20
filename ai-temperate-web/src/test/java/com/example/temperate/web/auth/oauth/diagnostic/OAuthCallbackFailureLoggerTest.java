package com.example.temperate.web.auth.oauth.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.temperate.service.auth.oauth.domain.OAuthProvider;
import com.example.temperate.service.auth.oauth.flow.OAuthClientPlatform;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowErrorCode;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowException;
import com.example.temperate.web.auth.oauth.provider.OAuthProviderErrorCode;
import com.example.temperate.web.auth.oauth.provider.OAuthProviderException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * 验证 OAuth Callback 诊断日志只包含稳定分类，不输出临时凭据和身份信息。
 */
class OAuthCallbackFailureLoggerTest {

    private Logger logger;
    private Level previousLevel;
    private boolean previousAdditive;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(OAuthCallbackFailureLogger.class);
        previousLevel = logger.getLevel();
        previousAdditive = logger.isAdditive();
        logger.setLevel(Level.WARN);
        logger.setAdditive(false);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MDC.put("traceId", "trace-oauth-callback-001");
    }

    @AfterEach
    void detachAppender() {
        MDC.clear();
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
        logger.setAdditive(previousAdditive);
    }

    @Test
    void providerFailureLogsStableCategoryWithoutThrowableMessage() {
        OAuthProviderException failure = new OAuthProviderException(
                OAuthProviderErrorCode.TOKEN_EXCHANGE_FAILED,
                "code=secret-code email=member@example.com token=secret-token");

        new OAuthCallbackFailureLogger().logFailure(
                OAuthProvider.GITHUB,
                OAuthClientPlatform.H5,
                failure,
                true,
                true,
                true);

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage()).contains(
                    "event=oauth_callback_failed",
                    "traceId=trace-oauth-callback-001",
                    "provider=GITHUB",
                    "platform=H5",
                    "failureCategory=TOKEN_EXCHANGE_FAILED",
                    "authorizationStateResolved=true",
                    "exceptionClass=" + OAuthProviderException.class.getName());
            assertThat(event.getFormattedMessage()).doesNotContain(
                    "secret-code", "member@example.com", "secret-token");
        });
    }

    @Test
    void missingStateAndHandshakeUseDedicatedSecurityCategories() {
        OAuthCallbackFailureLogger failureLogger = new OAuthCallbackFailureLogger();
        RuntimeException stateFailure = new OAuthFlowException(
                OAuthFlowErrorCode.FLOW_FORBIDDEN, "state value must not be logged");
        RuntimeException handshakeFailure = new OAuthFlowException(
                OAuthFlowErrorCode.FLOW_FORBIDDEN, "cookie value must not be logged");

        failureLogger.logFailure(
                OAuthProvider.GOOGLE,
                OAuthClientPlatform.H5,
                stateFailure,
                false,
                false,
                true);
        failureLogger.logFailure(
                OAuthProvider.GOOGLE,
                OAuthClientPlatform.H5,
                handshakeFailure,
                false,
                true,
                false);

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("failureCategory=STATE_INVALID"))
                .anyMatch(message -> message.contains("failureCategory=HANDSHAKE_INVALID"))
                .allMatch(message -> !message.contains("state value")
                        && !message.contains("cookie value"));
    }

    @Test
    void providerRejectionIsLoggedWithoutProviderControlledErrorText() {
        new OAuthCallbackFailureLogger().logAuthorizationRejected(
                OAuthProvider.GITHUB, OAuthClientPlatform.H5);

        assertThat(appender.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).contains(
                        "failureCategory=AUTHORIZATION_REJECTED",
                        "exceptionClass=absent"));
    }
}
