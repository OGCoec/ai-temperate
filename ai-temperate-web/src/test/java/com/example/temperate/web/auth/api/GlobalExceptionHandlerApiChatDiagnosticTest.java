package com.example.temperate.web.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.web.auth.flow.transport.AuthFlowCookieWriter;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import com.example.temperate.web.risk.PreAuthTransport;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 该测试是来确认 Chat 异常若落入通用 500 处理器会留下安全分类日志，同时保持原有通用错误响应不变。
 */
final class GlobalExceptionHandlerApiChatDiagnosticTest {

    @Test
    void recordsExactChatRouteSelectingUnexpectedHandlerWithoutExceptionMessage() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(
                Clock.systemUTC(),
                mock(AuthCookieWriter.class),
                mock(AuthFlowCookieWriter.class),
                mock(PreAuthTransport.class));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/v1/chat/completions");
        String previousTrace = MDC.get("apiChatTraceId");
        MDC.put("apiChatTraceId", "trace-global-handler");

        try (LogCapture logs = LogCapture.start()) {
            var response = handler.handleUnexpected(
                    ApiChatException.invalid("Function tool is invalid.", "tools"),
                    request);

            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(logs.joined())
                    .contains("event=api_chat_unexpected_handler_selected")
                    .contains("diagnosticSchema=chat-diag-v1")
                    .contains("traceId=trace-global-handler")
                    .contains("handler=GLOBAL_EXCEPTION_HANDLER")
                    .contains("exceptionType=com.example.temperate.service.user.apichat.ApiChatException")
                    .contains("status=500")
                    .doesNotContain("Function tool is invalid.");
        } finally {
            if (previousTrace == null) {
                MDC.remove("apiChatTraceId");
            } else {
                MDC.put("apiChatTraceId", previousTrace);
            }
        }
    }

    private record LogCapture(
            Logger logger,
            ListAppender<ILoggingEvent> appender) implements AutoCloseable {

        private static LogCapture start() {
            Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            return new LogCapture(logger, appender);
        }

        private String joined() {
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
