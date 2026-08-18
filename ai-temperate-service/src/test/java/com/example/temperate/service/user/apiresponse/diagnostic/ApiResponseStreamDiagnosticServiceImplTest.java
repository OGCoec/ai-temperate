package com.example.temperate.service.user.apiresponse.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.example.temperate.service.user.apiresponse.ApiResponseRequest;
import com.example.temperate.service.user.apiresponse.diagnostic.impl.ApiResponseStreamDiagnosticServiceImpl;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseSseFrame.TerminalKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * 该测试是来约束 Responses 诊断会话的嵌套 AOP 复用、惰性 Publisher 包装、背压元数据和失败日志脱敏边界。
 */
final class ApiResponseStreamDiagnosticServiceImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void reusesOneSessionAndReportsDemandMismatchWithoutSensitiveContent() throws Exception {
        ApiKeyProperties properties = properties(true, 1.0d);
        AtomicLong nanos = new AtomicLong();
        ApiResponseStreamDiagnosticServiceImpl diagnostics =
                new ApiResponseStreamDiagnosticServiceImpl(
                        properties, () -> nanos.addAndGet(100_000_000L));
        ApiResponseRequest request = objectMapper.readValue(
                "{\"model\":\"secret-model\",\"input\":\"secret-input\","
                        + "\"instructions\":\"secret-instructions\",\"stream\":true}",
                ApiResponseRequest.class);
        String previousTrace = MDC.get("apiChatTraceId");
        MDC.put("apiChatTraceId", "trace-responses-demand");

        try (LogCapture logs = LogCapture.start()) {
            ApiResponseDiagnosticInvocation controller = diagnostics.enter(
                    ApiResponseDiagnosticStage.HTTP_CONTROLLER,
                    new Object[] {request});
            ApiResponseDiagnosticInvocation service = diagnostics.enter(
                    ApiResponseDiagnosticStage.RESPONSE_SERVICE,
                    new Object[] {request});

            assertThat(controller.owner()).isTrue();
            assertThat(service.owner()).isFalse();
            assertThat(service.session()).isSameAs(controller.session());
            assertThat(diagnostics.currentSession()).isSameAs(controller.session());

            ApiResponseDiagnosticSession session = controller.session();
            session.recordBoundary(
                    ApiResponseDiagnosticBoundary.UPSTREAM_RAW,
                    120L,
                    ApiResponseFrameClass.LIFECYCLE,
                    0L,
                    TerminalKind.NONE,
                    false);
            session.recordBoundary(
                    ApiResponseDiagnosticBoundary.AFTER_PROTOCOL_PARSE,
                    0L,
                    ApiResponseFrameClass.LIFECYCLE,
                    0L,
                    TerminalKind.NONE,
                    false);
            session.recordBoundary(
                    ApiResponseDiagnosticBoundary.AFTER_BUSINESS_GATE,
                    0L,
                    ApiResponseFrameClass.LIFECYCLE,
                    0L,
                    TerminalKind.NONE,
                    false);
            session.recordBoundary(
                    ApiResponseDiagnosticBoundary.CONTROLLER_GATE_RECEIVED,
                    5L,
                    ApiResponseFrameClass.OUTPUT_TEXT,
                    1L,
                    TerminalKind.NONE,
                    false);
            session.recordBodySubscribed();
            session.recordDownstreamRequest(1L);
            session.recordUpstreamRequest(Long.MAX_VALUE);
            session.recordUpstreamRequest(9L);
            session.recordEmitAttempt(0L, 1L);
            RuntimeException failure = Exceptions.failWithOverflow(
                    "secret-output secret-function-arguments secret-reasoning");
            session.recordFailure(ApiResponseFailureStage.MVC_BODY, failure);
            session.summarize(SignalType.ON_ERROR, true);

            diagnostics.close(service);
            diagnostics.close(controller);

            String joined = logs.joined();
            assertThat(joined)
                    .contains("event=api_responses_stream_summary")
                    .contains("diagnosticSchema=responses-diag-v1")
                    .contains("traceId=trace-responses-demand")
                    .contains("mode=sse")
                    .contains("rawFrames=1")
                    .contains("parsedFrames=1")
                    .contains("businessFrames=1")
                    .contains("gateFrames=1")
                    .contains("bodyEmitAttempts=1")
                    .contains("bodyEmitSucceeded=0")
                    .contains("downstreamRequested=1")
                    .contains("upstreamRequested=9223372036854775807")
                    .contains("failureStage=MVC_BODY")
                    .contains("responseCommitted=true")
                    .doesNotContain(
                            "secret-model",
                            "secret-input",
                            "secret-instructions",
                            "secret-output",
                            "secret-function-arguments",
                            "secret-reasoning");
            assertThat(logs.count("event=api_responses_stream_summary")).isEqualTo(1);
            assertThat(logs.count("event=api_responses_stream_terminal_history"))
                    .isGreaterThanOrEqualTo(1);
        } finally {
            if (previousTrace == null) {
                MDC.remove("apiChatTraceId");
            } else {
                MDC.put("apiChatTraceId", previousTrace);
            }
        }
    }

    @Test
    void publisherObservationIsLazyAndDoesNotAddSubscriptions() {
        ApiResponseStreamDiagnosticServiceImpl diagnostics =
                new ApiResponseStreamDiagnosticServiceImpl(
                        properties(true, 1.0d), System::nanoTime);
        AtomicInteger fluxSubscriptions = new AtomicInteger();
        AtomicInteger monoSubscriptions = new AtomicInteger();
        ApiResponseDiagnosticInvocation invocation = diagnostics.enter(
                ApiResponseDiagnosticStage.RESPONSE_SERVICE,
                new Object[0]);
        Flux<String> flux = Flux.defer(() -> {
            fluxSubscriptions.incrementAndGet();
            return Flux.just("one");
        });
        Mono<String> mono = Mono.defer(() -> {
            monoSubscriptions.incrementAndGet();
            return Mono.just("one");
        });

        Flux<String> observedFlux = diagnostics.observeLifecycle(flux, invocation);
        Mono<String> observedMono = diagnostics.observeLifecycle(mono, invocation);

        assertThat(fluxSubscriptions).hasValue(0);
        assertThat(monoSubscriptions).hasValue(0);
        assertThat(observedFlux.blockLast()).isEqualTo("one");
        assertThat(observedMono.block()).isEqualTo("one");
        assertThat(fluxSubscriptions).hasValue(1);
        assertThat(monoSubscriptions).hasValue(1);
        diagnostics.close(invocation);
    }

    @Test
    void disabledDiagnosticsReturnOriginalPublishersAndLeaveNoCurrentSession() {
        ApiResponseStreamDiagnosticServiceImpl diagnostics =
                new ApiResponseStreamDiagnosticServiceImpl(
                        properties(false, 1.0d), System::nanoTime);
        Flux<String> flux = Flux.just("unchanged");
        Mono<String> mono = Mono.just("unchanged");

        try (LogCapture logs = LogCapture.start()) {
            ApiResponseDiagnosticInvocation invocation = diagnostics.enter(
                    ApiResponseDiagnosticStage.RESPONSE_SERVICE,
                    new Object[0]);

            assertThat(diagnostics.observeLifecycle(flux, invocation)).isSameAs(flux);
            assertThat(diagnostics.observeLifecycle(mono, invocation)).isSameAs(mono);
            diagnostics.close(invocation);
            assertThat(diagnostics.currentSession().enabled()).isFalse();
            assertThat(logs.joined()).isEmpty();
        }
    }

    @Test
    void unsampledFailureIsStillLoggedAndOriginalThrowablePropagates() {
        ApiResponseStreamDiagnosticServiceImpl diagnostics =
                new ApiResponseStreamDiagnosticServiceImpl(
                        properties(true, 0.0d), System::nanoTime);
        RuntimeException failure = new IllegalStateException("secret-exception-message");
        ApiResponseDiagnosticInvocation invocation = diagnostics.enter(
                ApiResponseDiagnosticStage.RESPONSE_SERVICE,
                new Object[0]);

        try (LogCapture logs = LogCapture.start()) {
            Flux<String> observed = diagnostics.observeLifecycle(
                    Flux.error(failure), invocation);

            assertThatThrownBy(observed::blockLast).isSameAs(failure);
            assertThat(logs.joined())
                    .contains("event=api_responses_stream_failure")
                    .contains("event=api_responses_stream_summary")
                    .contains("sampled=false")
                    .doesNotContain("secret-exception-message");
        } finally {
            diagnostics.close(invocation);
        }
    }

    @Test
    void unsampledCancellationStillLogsSummaryAndTerminalHistory() {
        ApiResponseStreamDiagnosticServiceImpl diagnostics =
                new ApiResponseStreamDiagnosticServiceImpl(
                        properties(true, 0.0d), System::nanoTime);
        ApiResponseDiagnosticInvocation invocation = diagnostics.enter(
                ApiResponseDiagnosticStage.RESPONSE_SERVICE,
                new Object[0]);

        try (LogCapture logs = LogCapture.start()) {
            ApiResponseDiagnosticSession session = invocation.session();
            session.recordClientCancelled();
            session.recordUpstreamCancelled();
            session.recordTerminalSignal("CLIENT_CANCEL");
            session.summarize(SignalType.CANCEL, true);

            assertThat(logs.joined())
                    .contains("event=api_responses_stream_summary")
                    .contains("signal=cancel")
                    .contains("clientCancelled=true")
                    .contains("upstreamCancelled=true")
                    .contains("event=api_responses_stream_terminal_history")
                    .contains("TERMINAL_SIGNAL:CLIENT_CANCEL");
        } finally {
            diagnostics.close(invocation);
        }
    }

    private static ApiKeyProperties properties(boolean enabled, double sampleRate) {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.getStreamDiagnostics().setEnabled(enabled);
        properties.getStreamDiagnostics().setSampleRate(sampleRate);
        properties.getStreamDiagnostics().setWindow(Duration.ofSeconds(1));
        properties.getStreamDiagnostics().setSilenceThreshold(Duration.ofSeconds(2));
        properties.getStreamDiagnostics().setBurstWindow(Duration.ofMillis(250));
        properties.getStreamDiagnostics().setTerminalHistorySize(32);
        properties.getStreamDiagnostics().setStackFrameLimit(12);
        return properties;
    }

    private record LogCapture(
            Logger sessionLogger,
            Logger serviceLogger,
            ListAppender<ILoggingEvent> appender) implements AutoCloseable {

        private static LogCapture start() {
            Logger sessionLogger = (Logger) LoggerFactory.getLogger(
                    ApiResponseDiagnosticSession.class);
            Logger serviceLogger = (Logger) LoggerFactory.getLogger(
                    ApiResponseStreamDiagnosticServiceImpl.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            sessionLogger.addAppender(appender);
            serviceLogger.addAppender(appender);
            return new LogCapture(sessionLogger, serviceLogger, appender);
        }

        private String joined() {
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
        }

        private long count(String marker) {
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains(marker))
                    .count();
        }

        @Override
        public void close() {
            sessionLogger.detachAppender(appender);
            serviceLogger.detachAppender(appender);
            appender.stop();
        }
    }
}
