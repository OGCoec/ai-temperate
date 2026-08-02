package com.example.temperate.service.user.aiconversation.diagnostic.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.temperate.service.user.aiconversation.config.AiConversationStreamDiagnosticsProperties;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingBoundary;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingContext;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingPath;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * 验证订阅级时序状态、爆发识别、调度器配对和安全日志边界。
 */
final class AiConversationStreamTimingDiagnosticServiceImplTest {

    @Test
    void disabledDiagnosticsReturnTheOriginalFluxWithoutState() {
        AiConversationStreamTimingDiagnosticServiceImpl diagnostics =
                new AiConversationStreamTimingDiagnosticServiceImpl(
                        properties(false, 0.0d, 200), System::nanoTime);
        Flux<String> source = Flux.just("one");

        assertThat(diagnostics.withSession(source, context())).isSameAs(source);
        assertThat(diagnostics.observeLifecycle(source)).isSameAs(source);
        assertThat(diagnostics.observeBoundary(
                source,
                AiConversationStreamTimingBoundary.SPRING_AI_RAW,
                String::length)).isSameAs(source);
    }

    @Test
    void recordsOneSummaryPerSubscriptionWithoutChangingSignals() {
        AtomicLong nanoTime = new AtomicLong();
        AiConversationStreamTimingDiagnosticServiceImpl diagnostics =
                new AiConversationStreamTimingDiagnosticServiceImpl(
                        properties(true, 1.0d, 200), nanoTime::get);
        ListAppender<ILoggingEvent> logs = attachLogs();
        Flux<String> source = Flux.just("a", "bb")
                .doOnNext(ignored -> nanoTime.addAndGet(10_000_000L));
        Flux<String> observed = diagnostics.withSession(
                diagnostics.observeLifecycle(
                        diagnostics.observeBoundary(
                                source,
                                AiConversationStreamTimingBoundary.SPRING_AI_RAW,
                                String::length)),
                context());

        StepVerifier.create(observed)
                .expectNext("a", "bb")
                .verifyComplete();
        StepVerifier.create(observed)
                .expectNext("a", "bb")
                .verifyComplete();

        assertThat(messages(logs, "event=ai_stream_timing_summary"))
                .hasSize(2)
                .allSatisfy(message -> assertThat(message)
                        .contains("usagePublicId=AZ-50wCZAQGBuCvbSqIYsA")
                        .contains("path=DIRECT_RESPONSE")
                        .contains("outcome=COMPLETE"));
    }

    @Test
    void identifiesSilenceFollowedByAChunkBurst() {
        AtomicLong nanoTime = new AtomicLong(Duration.ofSeconds(6).toNanos());
        AiConversationStreamTimingDiagnosticServiceImpl diagnostics =
                new AiConversationStreamTimingDiagnosticServiceImpl(
                        properties(true, 1.0d, 3), nanoTime::get);
        ListAppender<ILoggingEvent> logs = attachLogs();
        Flux<String> source = Flux.just("a", "b", "c")
                .doOnNext(ignored -> nanoTime.addAndGet(10_000_000L));
        Flux<String> observed = diagnostics.withSession(
                diagnostics.observeLifecycle(
                        diagnostics.observeBoundary(
                                source,
                                AiConversationStreamTimingBoundary.SPRING_AI_RAW,
                                String::length)),
                context(0L));

        StepVerifier.create(observed).expectNextCount(3).verifyComplete();

        assertThat(logs.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage())
                            .contains("event=ai_stream_timing_burst")
                            .contains("boundary=SPRING_AI_RAW")
                            .contains("chunkCount=3")
                            .contains("burst=true");
                });
    }

    @Test
    void schedulerBoundariesPairSignalsWithoutLoggingModelContent() {
        AtomicLong nanoTime = new AtomicLong(Duration.ofSeconds(1).toNanos());
        AiConversationStreamTimingDiagnosticServiceImpl diagnostics =
                new AiConversationStreamTimingDiagnosticServiceImpl(
                        properties(true, 1.0d, 200), nanoTime::get);
        ListAppender<ILoggingEvent> logs = attachLogs();
        String secretModelText = "PRIVATE_MODEL_TEXT_SHOULD_NOT_BE_LOGGED";
        Flux<String> raw = diagnostics.observeBoundary(
                Flux.just(secretModelText),
                AiConversationStreamTimingBoundary.SPRING_AI_RAW,
                String::length);
        Flux<String> afterScheduler = diagnostics.observeBoundary(
                raw.doOnNext(ignored -> nanoTime.addAndGet(25_000_000L)),
                AiConversationStreamTimingBoundary.AFTER_BOUNDED_ELASTIC,
                String::length);

        StepVerifier.create(diagnostics.withSession(
                        diagnostics.observeLifecycle(afterScheduler), context(0L)))
                .expectNext(secretModelText)
                .verifyComplete();

        assertThat(logs.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .allSatisfy(message -> assertThat(message)
                        .doesNotContain(secretModelText));
        assertThat(messages(logs, "event=ai_stream_timing_summary"))
                .singleElement(STRING)
                .contains("schedulerDelayMaxMs=25")
                .contains("pendingChunkMax=1");
    }

    private static AiConversationStreamDiagnosticsProperties properties(
            boolean enabled,
            double sampleRate,
            int burstChunks) {
        return new AiConversationStreamDiagnosticsProperties(
                enabled,
                sampleRate,
                Duration.ofSeconds(1),
                100,
                Duration.ofSeconds(5),
                Duration.ofMillis(250),
                burstChunks);
    }

    private static AiConversationStreamTimingContext context() {
        return context(0L);
    }

    private static AiConversationStreamTimingContext context(long startedNanos) {
        return new AiConversationStreamTimingContext(
                "trace-safe",
                "AZ-50wCZAQGBuCvbSqIYsA",
                "AZ-50wCLAQE49DmTkYw5dg",
                "ARAYbDiCEAA",
                AiConversationStreamTimingPath.DIRECT_RESPONSE,
                startedNanos);
    }

    private static ListAppender<ILoggingEvent> attachLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(
                AiConversationStreamTimingDiagnosticServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static java.util.List<String> messages(
            ListAppender<ILoggingEvent> logs,
            String marker) {
        return logs.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains(marker))
                .toList();
    }
}
