package com.example.temperate.service.user.apiresponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.aiinference.api.ApiInferenceExecutionRequest;
import com.example.temperate.service.user.aiinference.api.ApiInferenceLifecycleService;
import com.example.temperate.service.user.aiinference.api.ApiInferenceLifecycleSession;
import com.example.temperate.service.user.aiinference.api.ApiInferenceProtocol;
import com.example.temperate.service.user.aiinference.api.ApiInferenceReservation;
import com.example.temperate.service.user.aiinference.api.ApiInferenceUsage;
import com.example.temperate.service.user.aiinference.api.ApiInferenceUpstreamHeaders;
import com.example.temperate.service.user.aiinference.api.ApiInferenceUpstreamRequest;
import com.example.temperate.service.user.aiinference.concurrency.AiInferenceConcurrencyPermit;
import com.example.temperate.service.user.aiinference.sse.ApiInferenceSseEvent;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.example.temperate.service.user.apiresponse.impl.ApiResponseServiceImpl;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseDiagnosticInvocation;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseDiagnosticSession;
import com.example.temperate.service.user.apiresponse.diagnostic.ApiResponseDiagnosticStage;
import com.example.temperate.service.user.apiresponse.diagnostic.impl.ApiResponseStreamDiagnosticServiceImpl;
import com.example.temperate.service.user.apiresponse.provider.ApiResponseProviderAdapter;
import com.example.temperate.service.user.apiresponse.provider.ApiResponseProviderAdapterRegistry;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseSseFrame.TerminalKind;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseUpstreamClient;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseUpstreamJson;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseUpstreamStream;
import com.example.temperate.service.user.apiresponse.upstream.impl.ApiResponseProtocolParserImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.test.StepVerifier;

/**
 * 该测试是来锁定 Responses 状态机的严格序号、原生终态、结算前转发、协议错误退款和租约单次释放行为。
 */
final class ApiResponseServiceImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void forwardsCompletedWithoutChatDoneAndSettlesAuthoritativeUsage() throws Exception {
        Fixture fixture = fixture(Flux.just(
                event("response.output_text.delta", 0,
                        "\"delta\":\"hello\",\"output_index\":0"),
                completed(1)));

        ApiResponseCreation.Stream creation = (ApiResponseCreation.Stream)
                fixture.service().create(fixture.principal(), fixture.request());
        var frames = creation.response().block().body().collectList().block();

        assertThat(frames).hasSize(2);
        assertThat(frames.get(0).eventName()).isEqualTo("response.output_text.delta");
        assertThat(frames.get(1).terminalKind()).isEqualTo(TerminalKind.COMPLETED);
        assertThat(frames).noneMatch(frame -> "[DONE]".equals(frame.data()));
        assertThat(fixture.lifecycle().settled.get()).isEqualTo(1);
        assertThat(fixture.lifecycle().refunded.get()).isZero();
        assertThat(fixture.lifecycle().released.get()).isEqualTo(1);
        assertThat(fixture.lifecycle().lastUsage)
                .isEqualTo(new ApiInferenceUsage(12, 3, 2));
    }

    @Test
    void convertsDuplicateSequenceAfterStartToNativeErrorAndRefunds() throws Exception {
        Fixture fixture = fixture(Flux.just(
                event("response.output_text.delta", 0,
                        "\"delta\":\"hello\",\"output_index\":0"),
                event("response.output_text.done", 0,
                        "\"text\":\"hello\",\"output_index\":0")));

        ApiResponseCreation.Stream creation = (ApiResponseCreation.Stream)
                fixture.service().create(fixture.principal(), fixture.request());
        var frames = creation.response().block().body().collectList().block();

        assertThat(frames).hasSize(2);
        assertThat(frames.get(1).eventName()).isEqualTo("error");
        assertThat(frames.get(1).data()).contains("upstream_protocol_error");
        assertThat(fixture.lifecycle().refunded.get()).isEqualTo(1);
        assertThat(fixture.lifecycle().settled.get()).isZero();
        assertThat(fixture.lifecycle().released.get()).isEqualTo(1);
    }

    @Test
    void cancellationReleasesAndSchedulesLifecycleOnceWithoutSettlement() throws Exception {
        Fixture fixture = fixture(Flux.concat(
                Flux.just(event("response.output_text.delta", 0,
                        "\"delta\":\"hello\",\"output_index\":0")),
                Flux.never()));

        ApiResponseCreation.Stream creation = (ApiResponseCreation.Stream)
                fixture.service().create(fixture.principal(), fixture.request());

        StepVerifier.create(creation.response().block().body(), 1L)
                .expectNextMatches(frame ->
                        "response.output_text.delta".equals(frame.eventName()))
                .thenCancel()
                .verify();

        assertThat(fixture.lifecycle().released).hasValue(1);
        assertThat(fixture.lifecycle().cancellations).hasValue(1);
        assertThat(fixture.lifecycle().settled).hasValue(0);
        assertThat(fixture.lifecycle().refunded).hasValue(0);
    }

    @Test
    void nonStreamingIncompleteSettlesBeforeReturningOriginalJson() throws Exception {
        ObjectNode response = (ObjectNode) objectMapper.readTree("""
                {"object":"response","status":"incomplete","output":[],
                 "incomplete_details":{"reason":"max_output_tokens"},
                 "usage":{"input_tokens":12,"output_tokens":3,
                 "input_tokens_details":{"cached_tokens":2}}}
                """);
        Fixture fixture = fixture(Flux.empty(), Mono.just(response), false);

        ApiResponseCreation.Json creation = (ApiResponseCreation.Json)
                fixture.service().create(fixture.principal(), fixture.request());
        JsonNode returned = creation.response().block().body();

        assertThat(returned).isSameAs(response);
        assertThat(fixture.lifecycle().settled.get()).isEqualTo(1);
        assertThat(fixture.lifecycle().finishReason).isEqualTo("MAX_OUTPUT_TOKENS");
        assertThat(fixture.lifecycle().released.get()).isEqualTo(1);
    }

    @Test
    void nonStreamingFailedRefundsBeforeReturningOriginalFailureObject()
            throws Exception {
        ObjectNode response = (ObjectNode) objectMapper.readTree("""
                {"object":"response","status":"failed","output":[],
                 "error":{"code":"model_error","message":"Generation failed."}}
                """);
        Fixture fixture = fixture(Flux.empty(), Mono.just(response), false);

        ApiResponseCreation.Json creation = (ApiResponseCreation.Json)
                fixture.service().create(fixture.principal(), fixture.request());
        JsonNode returned = creation.response().block().body();

        assertThat(returned).isSameAs(response);
        assertThat(fixture.lifecycle().refunded).hasValue(1);
        assertThat(fixture.lifecycle().settled).hasValue(0);
        assertThat(fixture.lifecycle().released).hasValue(1);
    }

    @Test
    void recordsRawParsedAndBusinessBoundariesWithoutPayloadContent() throws Exception {
        Fixture fixture = fixture(Flux.just(
                event("response.output_text.delta", 0,
                        "\"delta\":\"secret-output\",\"output_index\":0"),
                completed(1)));
        ApiResponseDiagnosticInvocation invocation = fixture.diagnostics().enter(
                ApiResponseDiagnosticStage.RESPONSE_SERVICE,
                new Object[] {fixture.request()});

        try (LogCapture logs = LogCapture.start()) {
            ApiResponseCreation.Stream creation = (ApiResponseCreation.Stream)
                    fixture.service().create(fixture.principal(), fixture.request());
            creation.response().block().body().collectList().block();
            invocation.session().summarize(SignalType.ON_COMPLETE, false);

            assertThat(logs.joined())
                    .contains("event=api_responses_stream_summary")
                    .contains("rawFrames=2")
                    .contains("parsedFrames=2")
                    .contains("businessFrames=2")
                    .contains("terminalSeen=true")
                    .contains("usagePresent=true")
                    .doesNotContain("secret-output");
        } finally {
            fixture.diagnostics().close(invocation);
        }
    }

    @Test
    void validationFailureDoesNotStartLifecycleOrCallUpstream() throws Exception {
        ApiResponseRequest request = objectMapper.readValue(
                "{\"model\":\"gpt-test\",\"input\":\"hello\","
                        + "\"max_output_tokens\":15}",
                ApiResponseRequest.class);
        ApiKeyPrincipal principal = new ApiKeyPrincipal(
                new byte[16], 17L, new byte[32], "B".repeat(43), Set.of(23L));
        ApiResponseRequestValidator validator = mock(ApiResponseRequestValidator.class);
        ApiChatException failure = ApiChatException.invalid(
                "max_output_tokens is below the supported minimum.",
                "max_output_tokens",
                ApiChatException.ValidationReason.BELOW_MINIMUM);
        when(validator.validate(principal, request)).thenThrow(failure);
        ApiResponseProviderAdapter adapter = mock(ApiResponseProviderAdapter.class);
        when(adapter.type()).thenReturn(AiModelProvider.OPENAI);
        ApiResponseProviderAdapterRegistry registry =
                new ApiResponseProviderAdapterRegistry(Map.of("openai", adapter));
        ApiResponseUpstreamClient upstream = mock(ApiResponseUpstreamClient.class);
        ApiInferenceLifecycleService lifecycle = mock(ApiInferenceLifecycleService.class);
        com.example.temperate.service.user.apikey.config.ApiKeyProperties properties =
                new com.example.temperate.service.user.apikey.config.ApiKeyProperties();
        ApiResponseStreamDiagnosticServiceImpl diagnostics =
                new ApiResponseStreamDiagnosticServiceImpl(properties, System::nanoTime);
        ApiResponseService service = new ApiResponseServiceImpl(
                validator,
                registry,
                upstream,
                new ApiResponseProtocolParserImpl(objectMapper),
                lifecycle,
                objectMapper,
                new SimpleMeterRegistry(),
                diagnostics);

        assertThatThrownBy(() -> service.create(principal, request)).isSameAs(failure);
        verifyNoInteractions(lifecycle, upstream);
    }

    private Fixture fixture(Flux<ApiInferenceSseEvent> stream) throws Exception {
        return fixture(stream, Mono.empty(), true);
    }

    private Fixture fixture(
            Flux<ApiInferenceSseEvent> stream,
            Mono<JsonNode> json,
            boolean streaming) throws Exception {
        ApiResponseRequest request = objectMapper.readValue(
                "{\"model\":\"gpt-test\",\"input\":\"hello\",\"stream\":"
                        + streaming + "}",
                ApiResponseRequest.class);
        AiModelCacheEntry model = model();
        ValidatedApiResponseRequest validated = new ValidatedApiResponseRequest(
                request, model, 128, 32, streaming);
        ApiKeyPrincipal principal = new ApiKeyPrincipal(
                new byte[16], 17L, new byte[32], "B".repeat(43), Set.of(23L));
        ApiResponseRequestValidator validator = mock(ApiResponseRequestValidator.class);
        when(validator.validate(principal, request)).thenReturn(validated);
        ApiResponseProviderAdapter adapter = mock(ApiResponseProviderAdapter.class);
        when(adapter.type()).thenReturn(AiModelProvider.OPENAI);
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        when(adapter.adapt(validated)).thenReturn(payload);
        ApiResponseProviderAdapterRegistry registry =
                new ApiResponseProviderAdapterRegistry(Map.of("openai", adapter));
        ApiResponseUpstreamClient upstream = new ApiResponseUpstreamClient() {
            @Override
            public Mono<ApiResponseUpstreamStream> stream(
                    ObjectNode ignored,
                    ApiInferenceUpstreamRequest upstreamRequest) {
                return Mono.just(new ApiResponseUpstreamStream(
                        stream, ApiInferenceUpstreamHeaders.empty()));
            }

            @Override
            public Mono<ApiResponseUpstreamJson> create(
                    ObjectNode ignored,
                    ApiInferenceUpstreamRequest upstreamRequest) {
                return json.map(body -> new ApiResponseUpstreamJson(
                        body, ApiInferenceUpstreamHeaders.empty()));
            }
        };
        TestLifecycle lifecycle = new TestLifecycle(model, streaming);
        com.example.temperate.service.user.apikey.config.ApiKeyProperties properties =
                new com.example.temperate.service.user.apikey.config.ApiKeyProperties();
        properties.getStreamDiagnostics().setEnabled(true);
        properties.getStreamDiagnostics().setSampleRate(1.0d);
        ApiResponseStreamDiagnosticServiceImpl diagnostics =
                new ApiResponseStreamDiagnosticServiceImpl(properties, System::nanoTime);
        ApiResponseService service = new ApiResponseServiceImpl(
                validator,
                registry,
                upstream,
                new ApiResponseProtocolParserImpl(objectMapper),
                lifecycle,
                objectMapper,
                new SimpleMeterRegistry(),
                diagnostics);
        return new Fixture(service, principal, request, lifecycle, diagnostics);
    }

    private static ApiInferenceSseEvent event(
            String type,
            long sequence,
            String fields) {
        return new ApiInferenceSseEvent(type,
                "{\"type\":\"" + type + "\",\"sequence_number\":"
                        + sequence + "," + fields + "}");
    }

    private static ApiInferenceSseEvent completed(long sequence) {
        return new ApiInferenceSseEvent("response.completed",
                "{\"type\":\"response.completed\",\"sequence_number\":"
                        + sequence + ",\"response\":{\"object\":\"response\","
                        + "\"status\":\"completed\",\"output\":[],\"usage\":{"
                        + "\"input_tokens\":12,\"output_tokens\":3,"
                        + "\"input_tokens_details\":{\"cached_tokens\":2}}}}");
    }

    private static AiModelCacheEntry model() {
        return new AiModelCacheEntry(
                23L,
                "gpt-test",
                "openai",
                "test",
                null,
                List.of(),
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                8_192,
                512,
                List.of(AiModelCapabilityCode.RESPONSES));
    }

    private record Fixture(
            ApiResponseService service,
            ApiKeyPrincipal principal,
            ApiResponseRequest request,
            TestLifecycle lifecycle,
            ApiResponseStreamDiagnosticServiceImpl diagnostics) {
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

    /** 该夹具只记录生命周期调用次数，不执行数据库或 Redis I/O。 */
    private static final class TestLifecycle implements ApiInferenceLifecycleService {

        private final ApiInferenceLifecycleSession session;
        private final AtomicInteger settled = new AtomicInteger();
        private final AtomicInteger refunded = new AtomicInteger();
        private final AtomicInteger released = new AtomicInteger();
        private final AtomicInteger cancellations = new AtomicInteger();
        private ApiInferenceUsage lastUsage;
        private String finishReason;

        private TestLifecycle(AiModelCacheEntry model, boolean stream) {
            ApiInferenceExecutionRequest request = new ApiInferenceExecutionRequest(
                    model, 128, 32, stream,
                    ApiInferenceProtocol.RESPONSES);
            this.session = new ApiInferenceLifecycleSession(
                    new AiInferenceConcurrencyPermit(
                            HmacIdentifier.fromProtectedValue("A".repeat(43)),
                            HmacIdentifier.fromProtectedValue("B".repeat(43)),
                            "owner",
                            (short) 1),
                    new ApiInferenceReservation(
                            new byte[16], 17L, new byte[16], 2L, 32L,
                            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                            ApiInferenceProtocol.RESPONSES),
                    request);
        }

        @Override
        public ApiInferenceLifecycleSession start(
                ApiKeyPrincipal principal,
                ApiInferenceExecutionRequest request) {
            return session;
        }

        @Override
        public <T> Flux<T> withLeaseRenewal(
                Flux<T> source,
                ApiInferenceLifecycleSession ignored) {
            return source;
        }

        @Override
        public Mono<Void> settle(
                ApiInferenceLifecycleSession ignored,
                ApiInferenceUsage usage,
                String reason) {
            settled.incrementAndGet();
            lastUsage = usage;
            finishReason = reason;
            return Mono.empty();
        }

        @Override
        public Mono<Void> refundSystemFailure(
                ApiInferenceLifecycleSession ignored,
                String failureCode) {
            refunded.incrementAndGet();
            return Mono.empty();
        }

        @Override
        public void scheduleCancellation(
                ApiInferenceLifecycleSession ignored,
                ApiInferenceUsage usage,
                long emittedUtf8Bytes) {
            cancellations.incrementAndGet();
        }

        @Override
        public void release(ApiInferenceLifecycleSession ignored) {
            released.incrementAndGet();
        }
    }
}
