package com.example.temperate.service.user.aiconversation.diagnostic.impl;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureClassification;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureContext;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureDiagnosticAspect;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureDiagnosticService;
import com.example.temperate.service.user.aiconversation.diagnostic.AiUpstreamErrorDiagnostic;
import com.example.temperate.service.user.aiconversation.exception.AiUpstreamHttpStatusException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.HttpStatus;

/**
 * 验证跨 Bean 失败诊断只输出经过脱敏的上游字段，不展开异常消息或原始响应正文。
 */
final class AiConversationStreamFailureDiagnosticServiceImplTest {

    @Test
    void logsOneSanitizedUpstreamDiagnosticWithoutExceptionMessages() {
        AiUpstreamErrorDiagnostic diagnostic = new AiUpstreamErrorDiagnostic(
                "invalid_request_error",
                "extra_forbidden",
                "body.tools.0.search_context_size",
                "Extra inputs are not permitted",
                "req-safe",
                "application/json",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                128,
                false);
        AiUpstreamHttpStatusException upstream =
                new AiUpstreamHttpStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        diagnostic);
        RuntimeException wrapped = new RuntimeException(
                "RAW_EXCEPTION_MESSAGE_MUST_NOT_BE_LOGGED",
                upstream);
        ListAppender<ILoggingEvent> logs = attachLogs();
        AiConversationStreamFailureDiagnosticService service = proxiedService();

        AiConversationStreamFailureClassification result = service.diagnose(
                context(), wrapped);

        assertThat(result.upstreamDiagnostic()).isEqualTo(diagnostic);
        assertThat(logs.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .filteredOn(message -> message.contains(
                        "event=ai_conversation_stream_failed"))
                .singleElement()
                .satisfies(message -> assertThat(message)
                        .contains("upstreamStatus=422")
                        .contains("upstreamErrorCode=invalid_request_error")
                        .contains("upstreamErrorType=extra_forbidden")
                        .contains("upstreamErrorParam=body.tools.0.search_context_size")
                        .contains("upstreamErrorMessage=\"Extra inputs are not permitted\"")
                        .contains("upstreamRequestId=req-safe")
                        .contains("upstreamContentType=application/json")
                        .contains("upstreamBodySha256=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
                        .contains("upstreamCapturedBytes=128")
                        .contains("upstreamBodyTruncated=false")
                        .doesNotContain("RAW_EXCEPTION_MESSAGE_MUST_NOT_BE_LOGGED"));
    }

    private static AiConversationStreamFailureDiagnosticService proxiedService() {
        AiConversationStreamFailureDiagnosticServiceImpl target =
                new AiConversationStreamFailureDiagnosticServiceImpl(
                        new AiConversationStreamFailureClassifierImpl());
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new AiConversationStreamFailureDiagnosticAspect());
        return factory.getProxy();
    }

    private static AiConversationStreamFailureContext context() {
        return new AiConversationStreamFailureContext(
                "trace-safe",
                "usage-safe",
                "conversation-safe",
                "model-safe",
                "AI_UPSTREAM_STREAM_FAILED",
                true,
                0,
                0,
                10,
                "NOT_STARTED",
                "NOT_REQUIRED");
    }

    private static ListAppender<ILoggingEvent> attachLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(
                AiConversationStreamFailureDiagnosticAspect.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
