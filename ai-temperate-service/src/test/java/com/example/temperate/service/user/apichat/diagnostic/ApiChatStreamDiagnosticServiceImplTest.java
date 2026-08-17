package com.example.temperate.service.user.apichat.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.temperate.service.user.apichat.ApiChatRequest;
import com.example.temperate.service.user.apichat.billing.ApiChatBillingService.Usage;
import com.example.temperate.service.user.apichat.diagnostic.impl.ApiChatStreamDiagnosticServiceImpl;
import com.example.temperate.service.user.apichat.upstream.ApiChatSseParser.Normalization;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

/**
 * 该测试是来验证 API Chat 诊断能关联四个流边界、精确终止原因并确保日志不包含请求正文和完整密钥。
 */
final class ApiChatStreamDiagnosticServiceImplTest {

    @Test
    void recordsBoundariesUsageAndSingleTerminalSummaryWithoutContent() {
        ApiKeyProperties properties = properties(true);
        AtomicLong nanos = new AtomicLong();
        ApiChatStreamDiagnosticServiceImpl diagnostics =
                new ApiChatStreamDiagnosticServiceImpl(
                        properties, () -> nanos.addAndGet(100_000_000L));

        try (LogCapture logs = LogCapture.start()) {
            ApiChatDiagnosticInvocation invocation = diagnostics.enter(
                    ApiChatDiagnosticStage.COMPLETION_SERVICE,
                    new Object[] {principal(), request("secret prompt")});
            ApiChatDiagnosticSession session = invocation.session();
            session.recordUpstreamAttempted();
            session.recordUpstreamHeaders(200, "text/event-stream", true);
            session.recordBoundary(
                    ApiChatDiagnosticBoundary.UPSTREAM_RAW,
                    17,
                    ApiChatFrameKind.OUTPUT);
            session.recordBoundary(
                    ApiChatDiagnosticBoundary.AFTER_PROTOCOL_PARSE,
                    17,
                    ApiChatFrameKind.OUTPUT);
            session.recordUsage(new Usage(12, 3, 2), true);
            session.recordDone();
            diagnostics.returned(invocation, Flux.just("chunk"));
            diagnostics.close(invocation);

            diagnostics.observeLifecycle(Flux.just("chunk"), invocation)
                    .blockLast();

            String joined = logs.joined();
            assertThat(joined).contains("event=api_chat_stream_summary");
            assertThat(joined).contains("stage=COMPLETION_SERVICE");
            assertThat(joined).contains("rawFrames=1");
            assertThat(joined).contains("usageSeen=true");
            assertThat(joined).contains("doneSeen=true");
            assertThat(joined).contains("validationCompleted=true");
            assertThat(joined).contains("upstreamAttempted=true");
            assertThat(joined).contains("failureStage=none");
            assertThat(joined).doesNotContain("secret prompt");
            assertThat(joined).doesNotContain("sk-test-secret");
            assertThat(logs.count("event=api_chat_stream_summary")).isEqualTo(1);
        }
    }

    @Test
    void disabledDiagnosticsReturnsOriginalFluxWithoutLogs() {
        ApiChatStreamDiagnosticServiceImpl diagnostics =
                new ApiChatStreamDiagnosticServiceImpl(
                        properties(false), System::nanoTime);
        Flux<String> source = Flux.just("unchanged");

        try (LogCapture logs = LogCapture.start()) {
            ApiChatDiagnosticInvocation invocation = diagnostics.enter(
                    ApiChatDiagnosticStage.COMPLETION_SERVICE,
                    new Object[] {principal(), request("not logged")});
            Flux<String> observed = diagnostics.observeLifecycle(source, invocation);
            Flux<String> boundaryObserved = diagnostics.observeBoundary(
                    source,
                    ApiChatDiagnosticBoundary.UPSTREAM_RAW,
                    String::length,
                    ignored -> ApiChatFrameKind.DATA);
            diagnostics.close(invocation);

            assertThat(observed).isSameAs(source);
            assertThat(boundaryObserved).isSameAs(source);
            assertThat(logs.joined()).isEmpty();
        }
    }

    @Test
    void zeroSampleRateKeepsSuccessfulRequestsSilentButRetainsFailureBoundarySummary() {
        ApiKeyProperties properties = properties(true);
        properties.getStreamDiagnostics().setSampleRate(0.0d);
        ApiChatStreamDiagnosticServiceImpl diagnostics =
                new ApiChatStreamDiagnosticServiceImpl(properties, System::nanoTime);

        try (LogCapture logs = LogCapture.start()) {
            ApiChatDiagnosticInvocation success = diagnostics.enter(
                    ApiChatDiagnosticStage.COMPLETION_SERVICE,
                    new Object[] {principal(), request("success-secret")});
            diagnostics.returned(success, Flux.just("unchanged"));
            diagnostics.observeLifecycle(Flux.just("unchanged"), success).blockLast();
            assertThat(logs.joined()).isEmpty();

            ApiChatDiagnosticInvocation failure = diagnostics.enter(
                    ApiChatDiagnosticStage.COMPLETION_SERVICE,
                    new Object[] {principal(), request("failure-secret")});
            failure.session().recordUpstreamAttempted();
            failure.session().recordUpstreamHeaders(500, "text/plain; secret=value", false);
            diagnostics.returned(failure, Flux.error(new IllegalStateException("secret")));
            try {
                diagnostics.observeLifecycle(
                                Flux.error(new IllegalStateException("secret")), failure)
                        .blockLast();
            } catch (IllegalStateException ignored) {
                // 测试只读取旁路诊断；业务异常仍必须原样传播给订阅方。
            }

            assertThat(logs.joined())
                    .contains("event=api_chat_stream_summary")
                    .contains("upstreamAttempted=true")
                    .contains("upstreamStatus=500")
                    .contains("upstreamContentType=text/plain")
                    .contains("failureStage=COMPLETION_SERVICE")
                    .doesNotContain("success-secret", "failure-secret", "secret=value");
        }
    }

    @Test
    void recordsCombinedUsageNormalizationWithoutPayloadContent() {
        ApiChatDiagnosticSession session = new ApiChatDiagnosticSession(
                properties(true).getStreamDiagnostics(), System::nanoTime);

        try (LogCapture logs = LogCapture.start()) {
            session.recordNormalization(
                    Normalization.COMBINED_CHOICES_AND_USAGE,
                    2);

            assertThat(logs.joined())
                    .contains("event=api_chat_protocol_normalized")
                    .contains("normalization=COMBINED_CHOICES_AND_USAGE")
                    .contains("normalizedFrames=2")
                    .doesNotContain("choices")
                    .doesNotContain("\"usage\":");
        }
    }

    @Test
    void failedStreamWritesTerminalHistoryInBoundedEightEntryParts() {
        ApiKeyProperties properties = properties(true);
        properties.getStreamDiagnostics().setTerminalHistorySize(32);
        ApiChatDiagnosticSession session = new ApiChatDiagnosticSession(
                properties.getStreamDiagnostics(), System::nanoTime);

        try (LogCapture logs = LogCapture.start()) {
            for (int index = 0; index < 32; index++) {
                session.recordBoundary(
                        ApiChatDiagnosticBoundary.UPSTREAM_RAW,
                        index + 1L,
                        ApiChatFrameKind.DATA);
            }
            session.recordFailure(new ApiChatProtocolViolationException(
                    ApiChatProtocolViolation.STREAM_ENDED_WITHOUT_DONE));
            session.summarize(SignalType.ON_ERROR);

            List<String> summaries = logs.messages("event=api_chat_stream_summary");
            List<String> history = logs.messages(
                    "event=api_chat_stream_terminal_history");
            assertThat(summaries).hasSize(1);
            assertThat(summaries.get(0)).doesNotContain("terminalHistory=");
            assertThat(history).hasSize(4);
            assertThat(history.get(0)).contains("part=1 parts=4");
            assertThat(history.get(3)).contains("part=4 parts=4");
            assertThat(history).allSatisfy(message ->
                    assertThat(message.substring(message.indexOf("entries=") + 8)
                            .split(","))
                            .hasSizeLessThanOrEqualTo(8));
        }
    }

    private static ApiKeyProperties properties(boolean enabled) {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.getStreamDiagnostics().setEnabled(enabled);
        properties.getStreamDiagnostics().setWindow(Duration.ofSeconds(1));
        properties.getStreamDiagnostics().setLogEveryFrames(100);
        return properties;
    }

    private static ApiKeyPrincipal principal() {
        return new ApiKeyPrincipal(
                11L,
                17L,
                new byte[32],
                "B".repeat(43),
                Set.of(23L));
    }

    private static ApiChatRequest request(String content) {
        return new ApiChatRequest(
                "gpt-test",
                List.of(new ApiChatRequest.Message(
                        "user",
                        JsonNodeFactory.instance.textNode(content),
                        null,
                        null,
                        null)),
                JsonNodeFactory.instance.booleanNode(true),
                new ApiChatRequest.StreamOptions(
                        JsonNodeFactory.instance.booleanNode(true)),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private record LogCapture(
            Logger logger,
            ListAppender<ILoggingEvent> appender) implements AutoCloseable {

        private static LogCapture start() {
            Logger logger = (Logger) LoggerFactory.getLogger(
                    ApiChatDiagnosticSession.class);
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

        private long count(String marker) {
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains(marker))
                    .count();
        }

        private List<String> messages(String marker) {
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains(marker))
                    .toList();
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
