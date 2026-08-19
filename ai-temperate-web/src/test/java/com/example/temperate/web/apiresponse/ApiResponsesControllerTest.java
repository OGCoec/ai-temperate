package com.example.temperate.web.apiresponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.temperate.service.user.aiinference.api.ApiInferenceUsage;
import com.example.temperate.service.user.apichat.ApiChatErrorCode;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.example.temperate.service.user.apiresponse.ApiResponseCreation;
import com.example.temperate.service.user.apiresponse.ApiResponseService;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseDiagnosticInvocation;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseDiagnosticSession;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseDiagnosticStage;
import com.example.temperate.service.user.apiresponse.diagnostic.impl.ApiResponseStreamDiagnosticServiceImpl;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseSseFrame;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseSseFrame.TerminalKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * 该测试是来约束 Responses Controller 的动态 JSON/SSE Content-Type、原生 event 名称、无缓存头和仅流式防缓冲头。
 */
final class ApiResponsesControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void returnsJsonWithoutSseHeadersAfterServiceCompletion() throws Exception {
        ObjectNode request = request(false);
        ObjectNode body = objectMapper.createObjectNode().put("object", "response");
        ApiResponsesController controller = new ApiResponsesController(
                (principal, ignored, clientRequestId) ->
                        new ApiResponseCreation.Json(body),
                diagnostics(false));

        var response = controller.create(principal(), request).block();

        assertThat(response).isNotNull();
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getHeaders().getFirst("X-Accel-Buffering")).isNull();
        assertThat(response.getHeaders().getFirst("CDN-Cache-Control")).isEqualTo("no-store");
        assertThat(response.getBody()).isSameAs(body);
    }

    @Test
    void returnsSseWithNativeEventNameAndNoChatDone() throws Exception {
        ObjectNode request = request(true);
        ApiResponseSseFrame delta = new ApiResponseSseFrame(
                "response.output_text.delta",
                "{\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}",
                5,
                0,
                TerminalKind.NONE,
                null,
                null);
        ApiResponseSseFrame frame = new ApiResponseSseFrame(
                "response.completed",
                "{\"type\":\"response.completed\"}",
                0,
                1,
                TerminalKind.COMPLETED,
                new ApiInferenceUsage(1, 1, 0),
                "STOP");
        AtomicLong maximumUpstreamRequest = new AtomicLong();
        ApiResponseService service = (principal, ignored, clientRequestId) ->
                new ApiResponseCreation.Stream(Flux.just(delta, frame)
                        .doOnRequest(requested -> maximumUpstreamRequest
                                .accumulateAndGet(requested, Math::max)));
        ApiResponsesController controller = new ApiResponsesController(
                service, diagnostics(false));

        var response = controller.create(principal(), request).block();
        @SuppressWarnings("unchecked")
        Flux<ServerSentEvent<String>> body =
                (Flux<ServerSentEvent<String>>) response.getBody();
        var events = body.collectList().block();

        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.TEXT_EVENT_STREAM);
        assertThat(response.getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");
        assertThat(events).hasSize(2);
        assertThat(events.get(0).event()).isEqualTo("response.output_text.delta");
        assertThat(events.get(1).event()).isEqualTo("response.completed");
        assertThat(events).allSatisfy(event -> assertThat(event.data()).doesNotContain("[DONE]"));
        assertThat(maximumUpstreamRequest).hasValue(1L);
    }

    @Test
    void propagatesFailureBeforeFirstSseEventWithoutCreatingSuccessResponse() throws Exception {
        ObjectNode request = request(true);
        ApiChatException failure = new ApiChatException(
                ApiChatErrorCode.UPSTREAM_UNAVAILABLE,
                "The model upstream is unavailable.",
                null);
        ApiResponsesController controller = new ApiResponsesController(
                (principal, ignored, clientRequestId) -> new ApiResponseCreation.Stream(
                        Flux.error(failure)),
                diagnostics(false));

        assertThatThrownBy(() -> controller.create(principal(), request).block())
                .isSameAs(failure);
    }

    @Test
    void rejectsAnEmptyUpstreamBeforeCreatingSuccessResponse() throws Exception {
        ObjectNode request = request(true);
        ApiResponsesController controller = new ApiResponsesController(
                (principal, ignored, clientRequestId) ->
                        new ApiResponseCreation.Stream(Flux.empty()),
                diagnostics(false));

        assertThatThrownBy(() -> controller.create(principal(), request).block())
                .isInstanceOf(ApiChatException.class)
                .hasMessageContaining("ended without a Responses event");
    }

    @Test
    void emitsATerminalFirstFrameThenCompletesWithoutCancellingUpstream() throws Exception {
        ObjectNode request = request(true);
        ApiResponseSseFrame terminal = new ApiResponseSseFrame(
                "response.completed",
                "{\"type\":\"response.completed\"}",
                0L,
                0L,
                TerminalKind.COMPLETED,
                new ApiInferenceUsage(1L, 1L, 0L),
                "STOP");
        AtomicInteger upstreamCancellations = new AtomicInteger();
        ApiResponsesController controller = new ApiResponsesController(
                (principal, ignored, clientRequestId) -> new ApiResponseCreation.Stream(
                        Flux.just(terminal)
                                .doOnCancel(upstreamCancellations::incrementAndGet)),
                diagnostics(false));

        var response = controller.create(principal(), request).block();
        @SuppressWarnings("unchecked")
        Flux<ServerSentEvent<String>> body =
                (Flux<ServerSentEvent<String>>) response.getBody();

        StepVerifier.create(body, 0L)
                .thenRequest(1L)
                .expectNextMatches(event -> "response.completed".equals(event.event()))
                .verifyComplete();
        assertThat(upstreamCancellations).hasValue(0);
    }

    @Test
    void emitsTheFirstFrameBeforeCompletingWhenUpstreamAlreadyCompleted() throws Exception {
        ObjectNode request = request(true);
        ApiResponseSseFrame first = new ApiResponseSseFrame(
                "response.created",
                "{\"type\":\"response.created\"}",
                0L,
                0L,
                TerminalKind.NONE,
                null,
                null);
        ApiResponsesController controller = new ApiResponsesController(
                (principal, ignored, clientRequestId) ->
                        new ApiResponseCreation.Stream(Flux.just(first)),
                diagnostics(false));

        var response = controller.create(principal(), request).block();
        @SuppressWarnings("unchecked")
        Flux<ServerSentEvent<String>> body =
                (Flux<ServerSentEvent<String>>) response.getBody();

        StepVerifier.create(body, 0L)
                .thenRequest(1L)
                .expectNextMatches(event -> "response.created".equals(event.event()))
                .verifyComplete();
    }

    @Test
    void emitsTheFirstFrameBeforePropagatingAStoredUpstreamFailure() throws Exception {
        ObjectNode request = request(true);
        ApiResponseSseFrame first = new ApiResponseSseFrame(
                "response.created",
                "{\"type\":\"response.created\"}",
                0L,
                0L,
                TerminalKind.NONE,
                null,
                null);
        RuntimeException failure = new IllegalStateException("upstream failed after first");
        Flux<ApiResponseSseFrame> upstream = Flux.concat(
                Flux.just(first), Flux.error(failure));
        ApiResponsesController controller = new ApiResponsesController(
                (principal, ignored, clientRequestId) ->
                        new ApiResponseCreation.Stream(upstream),
                diagnostics(false));

        var response = controller.create(principal(), request).block();
        @SuppressWarnings("unchecked")
        Flux<ServerSentEvent<String>> body =
                (Flux<ServerSentEvent<String>>) response.getBody();

        StepVerifier.create(body, 1L)
                .expectNextMatches(event -> "response.created".equals(event.event()))
                .expectErrorMatches(actual -> actual == failure)
                .verify();
    }

    @Test
    void cancelsUpstreamWhenTheHttpMonoIsCancelledBeforeTheFirstFrame() throws Exception {
        ObjectNode request = request(true);
        AtomicInteger upstreamCancellations = new AtomicInteger();
        Flux<ApiResponseSseFrame> upstream = Flux.<ApiResponseSseFrame>never()
                .doOnCancel(upstreamCancellations::incrementAndGet);
        ApiResponsesController controller = new ApiResponsesController(
                (principal, ignored, clientRequestId) ->
                        new ApiResponseCreation.Stream(upstream),
                diagnostics(false));

        StepVerifier.create(controller.create(principal(), request))
                .thenCancel()
                .verify();

        assertThat(upstreamCancellations).hasValue(1);
    }

    @Test
    void streamsOneFramePerDemandWithoutOverflow() throws Exception {
        ObjectNode request = request(true);
        ApiResponseSseFrame first = new ApiResponseSseFrame(
                "response.created",
                "{\"type\":\"response.created\",\"secret\":\"secret-created\"}",
                0L,
                0L,
                TerminalKind.NONE,
                null,
                null);
        ApiResponseSseFrame second = new ApiResponseSseFrame(
                "response.output_text.delta",
                "{\"type\":\"response.output_text.delta\",\"delta\":\"secret-delta\"}",
                6L,
                1L,
                TerminalKind.NONE,
                null,
                null);
        ApiResponseSseFrame third = new ApiResponseSseFrame(
                "response.completed",
                "{\"type\":\"response.completed\",\"secret\":\"secret-completed\"}",
                0L,
                2L,
                TerminalKind.COMPLETED,
                new ApiInferenceUsage(8L, 3L, 1L),
                "STOP");
        ApiResponseStreamDiagnosticServiceImpl diagnostics = diagnostics(true);
        ApiResponseDiagnosticInvocation invocation = diagnostics.enter(
                ApiResponseDiagnosticStage.HTTP_CONTROLLER,
                new Object[] {request});
        ApiResponsesController controller = new ApiResponsesController(
                (principal, ignored, clientRequestId) -> new ApiResponseCreation.Stream(
                        Flux.just(first, second, third)),
                diagnostics);

        try (LogCapture logs = LogCapture.start()) {
            var response = controller.create(principal(), request).block();
            diagnostics.close(invocation);
            @SuppressWarnings("unchecked")
            Flux<ServerSentEvent<String>> body =
                    (Flux<ServerSentEvent<String>>) response.getBody();

            StepVerifier.create(body, 1L)
                    .expectNextMatches(event -> "response.created".equals(event.event()))
                    .thenRequest(1L)
                    .expectNextMatches(event ->
                            "response.output_text.delta".equals(event.event()))
                    .thenRequest(1L)
                    .expectNextMatches(event -> "response.completed".equals(event.event()))
                    .verifyComplete();

            assertThat(logs.joined())
                    .contains("event=api_responses_stream_summary")
                    .contains("gateFrames=3")
                    .contains("bodyEmitAttempts=3")
                    .contains("bodyEmitSucceeded=3")
                    .contains("downstreamRequested=3")
                    .contains("upstreamRequested=3")
                    .doesNotContain(
                            "OverflowException",
                            "failureStage=MVC_BODY",
                            "secret-created",
                            "secret-delta",
                            "secret-completed");
        } finally {
            diagnostics.close(invocation);
        }
    }

    @Test
    void waitsForBodyDemandBeforeRequestingTheNextUpstreamFrame() throws Exception {
        ObjectNode request = request(true);
        ApiResponseSseFrame first = new ApiResponseSseFrame(
                "response.created",
                "{\"type\":\"response.created\"}",
                0L,
                0L,
                TerminalKind.NONE,
                null,
                null);
        ApiResponseSseFrame terminal = new ApiResponseSseFrame(
                "response.completed",
                "{\"type\":\"response.completed\"}",
                0L,
                1L,
                TerminalKind.COMPLETED,
                new ApiInferenceUsage(1L, 1L, 0L),
                "STOP");
        AtomicLong upstreamRequested = new AtomicLong();
        Flux<ApiResponseSseFrame> upstream = Flux.just(first, terminal)
                .doOnRequest(upstreamRequested::addAndGet);
        ApiResponsesController controller = new ApiResponsesController(
                (principal, ignored, clientRequestId) ->
                        new ApiResponseCreation.Stream(upstream),
                diagnostics(false));

        var response = controller.create(principal(), request).block();
        assertThat(upstreamRequested).hasValue(1L);
        @SuppressWarnings("unchecked")
        Flux<ServerSentEvent<String>> body =
                (Flux<ServerSentEvent<String>>) response.getBody();

        StepVerifier.create(body, 0L)
                .then(() -> assertThat(upstreamRequested).hasValue(1L))
                .thenRequest(1L)
                .expectNextMatches(event -> "response.created".equals(event.event()))
                .then(() -> assertThat(upstreamRequested).hasValue(1L))
                .thenRequest(1L)
                .expectNextMatches(event -> "response.completed".equals(event.event()))
                .verifyComplete();

        assertThat(upstreamRequested).hasValue(2L);
    }

    @Test
    void cancelsTheUpstreamOnceWhenTheBodySubscriberCancels() throws Exception {
        ObjectNode request = request(true);
        ApiResponseSseFrame first = new ApiResponseSseFrame(
                "response.created",
                "{\"type\":\"response.created\"}",
                0L,
                0L,
                TerminalKind.NONE,
                null,
                null);
        AtomicInteger upstreamCancellations = new AtomicInteger();
        Flux<ApiResponseSseFrame> upstream = Flux
                .concat(Flux.just(first), Flux.<ApiResponseSseFrame>never())
                .doOnCancel(upstreamCancellations::incrementAndGet);
        ApiResponseStreamDiagnosticServiceImpl diagnostics = diagnostics(true);
        ApiResponseDiagnosticInvocation invocation = diagnostics.enter(
                ApiResponseDiagnosticStage.HTTP_CONTROLLER,
                new Object[] {request});
        ApiResponsesController controller = new ApiResponsesController(
                (principal, ignored, clientRequestId) ->
                        new ApiResponseCreation.Stream(upstream),
                diagnostics);

        try (LogCapture logs = LogCapture.start()) {
            var response = controller.create(principal(), request).block();
            diagnostics.close(invocation);
            @SuppressWarnings("unchecked")
            Flux<ServerSentEvent<String>> body =
                    (Flux<ServerSentEvent<String>>) response.getBody();

            StepVerifier.create(body, 1L)
                    .expectNextMatches(event -> "response.created".equals(event.event()))
                    .thenCancel()
                    .verify();

            assertThat(upstreamCancellations).hasValue(1);
            assertThat(logs.joined())
                    .contains("TERMINAL_SIGNAL:CLIENT_CANCEL")
                    .contains("clientCancelled=true")
                    .contains("upstreamCancelled=true")
                    .contains("signal=cancel")
                    .doesNotContain("OverflowException");
        } finally {
            diagnostics.close(invocation);
        }
    }

    private ObjectNode request(boolean stream) throws Exception {
        return (ObjectNode) objectMapper.readTree(
                "{\"model\":\"gpt-test\",\"input\":\"hello\",\"stream\":"
                        + stream + "}");
    }

    private static ApiKeyPrincipal principal() {
        return new ApiKeyPrincipal(
                new byte[16], 17L, new byte[32], "B".repeat(43), Set.of(7L));
    }

    private static ApiResponseStreamDiagnosticServiceImpl diagnostics(
            boolean enabled) {
        com.example.temperate.service.user.apikey.config.ApiKeyProperties properties =
                new com.example.temperate.service.user.apikey.config.ApiKeyProperties();
        properties.getStreamDiagnostics().setEnabled(enabled);
        properties.getStreamDiagnostics().setSampleRate(1.0d);
        return new ApiResponseStreamDiagnosticServiceImpl(
                properties, System::nanoTime);
    }

    private record LogCapture(
            Logger logger,
            ListAppender<ILoggingEvent> appender) implements AutoCloseable {

        private static LogCapture start() {
            Logger logger = (Logger) LoggerFactory.getLogger(
                    ApiResponseDiagnosticSession.class);
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
