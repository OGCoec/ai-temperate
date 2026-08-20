package com.example.temperate.service.auth.oauth.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.oauth.domain.OAuthProofType;
import com.example.temperate.service.auth.oauth.domain.OAuthProvider;
import com.example.temperate.service.auth.oauth.domain.TrustedOAuthIdentity;
import com.example.temperate.service.auth.oauth.identity.OAuthAccountDecision;
import com.example.temperate.service.auth.oauth.identity.OAuthAccountDecisionType;
import com.example.temperate.service.auth.oauth.identity.OAuthProviderCompletionService;
import java.sql.SQLException;
import org.apache.ibatis.exceptions.PersistenceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.mybatis.spring.MyBatisSystemException;

/**
 * 验证 OAuth Provider 完成切面只输出安全结构化元数据，并严格清理线程内诊断阶段。
 */
class OAuthAccountResolutionDiagnosticAspectTest {

    private Logger logger;
    private Level previousLevel;
    private boolean previousAdditive;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(
                OAuthAccountResolutionDiagnosticAspect.class);
        previousLevel = logger.getLevel();
        previousAdditive = logger.isAdditive();
        logger.setLevel(Level.DEBUG);
        logger.setAdditive(false);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MDC.put("traceId", "trace-oauth-resolution-001");
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
    void logsEmailLookupRootCauseWithoutThrowableOrSensitiveValues() {
        SQLException sqlFailure = new SQLException(
                "email=member@example.com subject=secret-subject token=secret-token",
                "22007");
        sqlFailure.setStackTrace(new StackTraceElement[] {
            new StackTraceElement(
                    "org.postgresql.jdbc.PgResultSet",
                    "getOffsetDateTime",
                    "PgResultSet.java",
                    741)
        });
        MyBatisSystemException expected = new MyBatisSystemException(
                new PersistenceException(sqlFailure));
        OAuthProviderCompletionService target = (flowId, identity) -> {
            OAuthAccountResolutionDiagnosticContext.mark(
                    OAuthAccountResolutionDiagnosticContext.Stage.EMAIL_LOOKUP);
            throw expected;
        };
        OAuthProviderCompletionService proxy = proxy(target);

        assertThatThrownBy(() -> proxy.accept(flowId(), identity()))
                .isSameAs(expected);

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage()).contains(
                    "event=oauth_account_resolution_failed",
                    "diagnosticSchema=oauth-account-resolution-v1",
                    "traceId=trace-oauth-resolution-001",
                    "provider=GITHUB",
                    "stage=EMAIL_LOOKUP",
                    "failureCategory=DATA_CONVERSION_FAILURE",
                    "exceptionClass=" + MyBatisSystemException.class.getName(),
                    "rootExceptionClass=" + SQLException.class.getName(),
                    "rootSourceClass=org.postgresql.jdbc.PgResultSet",
                    "rootSourceMethod=getOffsetDateTime",
                    "sqlState=22007");
            assertThat(event.getFormattedMessage()).doesNotContain(
                    "member@example.com",
                    "secret-subject",
                    "secret-token",
                    "PgResultSet.java",
                    "741");
        });
        assertThat(OAuthAccountResolutionDiagnosticContext.currentStage()).isNull();
    }

    @Test
    void successfulInvocationReturnsOriginalResultAndDoesNotLeakContext() {
        OAuthAccountDecision expected = new OAuthAccountDecision(
                OAuthAccountDecisionType.AUTHENTICATE,
                OAuthProvider.GITHUB,
                41L,
                false);
        OAuthProviderCompletionService target = (flowId, identity) -> {
            OAuthAccountResolutionDiagnosticContext.mark(
                    OAuthAccountResolutionDiagnosticContext.Stage.FLOW_PERSISTENCE);
            return expected;
        };
        OAuthProviderCompletionService proxy = proxy(target);

        OAuthAccountDecision actual = proxy.accept(flowId(), identity());

        assertThat(actual).isSameAs(expected);
        assertThat(appender.list).isEmpty();
        assertThat(OAuthAccountResolutionDiagnosticContext.currentStage()).isNull();
    }

    @Test
    void nestedAndRepeatedScopesRestorePreviousStage() {
        try (OAuthAccountResolutionDiagnosticContext.Scope ignored =
                OAuthAccountResolutionDiagnosticContext.open()) {
            OAuthAccountResolutionDiagnosticContext.mark(
                    OAuthAccountResolutionDiagnosticContext.Stage.SUBJECT_LOOKUP);
            OAuthProviderCompletionService proxy = proxy((flowId, identity) -> {
                OAuthAccountResolutionDiagnosticContext.mark(
                        OAuthAccountResolutionDiagnosticContext.Stage.AUTH_CONTEXT_LOOKUP);
                return new OAuthAccountDecision(
                        OAuthAccountDecisionType.AUTHENTICATE,
                        OAuthProvider.GITHUB,
                        52L,
                        false);
            });

            proxy.accept(flowId(), identity());

            assertThat(OAuthAccountResolutionDiagnosticContext.currentStage())
                    .isEqualTo(OAuthAccountResolutionDiagnosticContext.Stage.SUBJECT_LOOKUP);
        }
        assertThat(OAuthAccountResolutionDiagnosticContext.currentStage()).isNull();
    }

    private static OAuthProviderCompletionService proxy(
            OAuthProviderCompletionService target) {
        AspectJProxyFactory factory = new AspectJProxyFactory();
        factory.setTarget(target);
        factory.setInterfaces(OAuthProviderCompletionService.class);
        factory.setProxyTargetClass(false);
        factory.addAspect(new OAuthAccountResolutionDiagnosticAspect(
                new OAuthAccountResolutionFailureClassifier()));
        return OAuthProviderCompletionService.class.cast(factory.getProxy());
    }

    private static HmacIdentifier flowId() {
        return HmacIdentifier.fromProtectedValue("A".repeat(43));
    }

    private static TrustedOAuthIdentity identity() {
        return new TrustedOAuthIdentity(
                OAuthProvider.GITHUB,
                "secret-subject",
                "member@example.com",
                true,
                OAuthProofType.BROWSER_AUTHORIZATION_CODE);
    }
}
