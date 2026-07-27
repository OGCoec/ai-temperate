package com.example.temperate.web.admin.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconErrorCode;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;

/**
 * 验证管理员已知异常只产生按 HTTP 状态分级的安全单行日志，不附带堆栈或异常消息。
 */
final class AdminExceptionLoggerTest {

    private Logger logger;
    private Level previousLevel;
    private boolean previousAdditive;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(AdminExceptionLogger.class);
        previousLevel = logger.getLevel();
        previousAdditive = logger.isAdditive();
        logger.setLevel(Level.TRACE);
        logger.setAdditive(false);

        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        MDC.clear();
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
        logger.setAdditive(previousAdditive);
    }

    @ParameterizedTest
    @MethodSource("knownFailureLevels")
    void knownFailureUsesExpectedLevelWithoutThrowableOrSensitiveMessage(
            AiModelIconErrorCode code,
            HttpStatus httpStatus,
            Level expectedLevel) {
        MDC.put("traceId", "trace-admin-001");
        RuntimeException failure = new RuntimeException(
                "business wrapper",
                new IllegalArgumentException(
                        "https://secret.example/icon.png?access_token=do-not-log"));

        new AdminExceptionLogger().logKnown(
                "admin_ai_model_icon_rejected",
                code.name(),
                httpStatus,
                failure);

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(expectedLevel);
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage())
                    .contains(
                            "event=admin_ai_model_icon_rejected",
                            "code=" + code.name(),
                            "httpStatus=" + httpStatus.value(),
                            "traceId=trace-admin-001",
                            "exceptionType=java.lang.IllegalArgumentException",
                            "rootCauseType=java.lang.IllegalArgumentException")
                    .doesNotContain(
                            "secret.example",
                            "access_token",
                            "do-not-log",
                            "business wrapper");
        });
    }

    @Test
    void missingTraceIdUsesAbsentSentinel() {
        new AdminExceptionLogger().logKnown(
                "admin_ai_model_rejected",
                "AI_MODEL_NOT_FOUND",
                HttpStatus.NOT_FOUND,
                new IllegalStateException("must not be logged"));

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage())
                    .contains("traceId=absent")
                    .doesNotContain("must not be logged");
        });
    }

    private static Stream<Arguments> knownFailureLevels() {
        return Stream.of(
                Arguments.of(
                        AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_HOST_NOT_PUBLIC,
                        HttpStatus.BAD_REQUEST,
                        Level.INFO),
                Arguments.of(
                        AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_FORMAT_UNSUPPORTED,
                        HttpStatus.BAD_REQUEST,
                        Level.INFO),
                Arguments.of(
                        AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_UNSAFE,
                        HttpStatus.BAD_REQUEST,
                        Level.INFO),
                Arguments.of(
                        AiModelIconErrorCode.AI_MODEL_ICON_FILE_TOO_LARGE,
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        Level.INFO),
                Arguments.of(
                        AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_DNS_RESOLUTION_FAILED,
                        HttpStatus.BAD_GATEWAY,
                        Level.WARN),
                Arguments.of(
                        AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_TLS_HANDSHAKE_FAILED,
                        HttpStatus.BAD_GATEWAY,
                        Level.WARN),
                Arguments.of(
                        AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_CONNECT_FAILED,
                        HttpStatus.BAD_GATEWAY,
                        Level.WARN),
                Arguments.of(
                        AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_HTTP_STATUS_INVALID,
                        HttpStatus.BAD_GATEWAY,
                        Level.WARN),
                Arguments.of(
                        AiModelIconErrorCode.AI_MODEL_ICON_STORAGE_UNAVAILABLE,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        Level.WARN),
                Arguments.of(
                        AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_READ_TIMEOUT,
                        HttpStatus.GATEWAY_TIMEOUT,
                        Level.WARN));
    }
}
