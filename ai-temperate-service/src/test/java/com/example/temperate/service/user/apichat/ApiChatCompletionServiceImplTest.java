package com.example.temperate.service.user.apichat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.aiinference.api.ApiInferenceExecutionRequest;
import com.example.temperate.service.user.aiinference.api.ApiInferenceProtocol;
import com.example.temperate.service.user.aiinference.api.ApiInferenceReservation;
import com.example.temperate.service.user.aiinference.api.ApiInferenceUsage;
import com.example.temperate.service.user.aiinference.api.ApiInferenceUpstreamHeaders;
import com.example.temperate.service.user.aiinference.api.ApiInferenceUpstreamRequest;
import com.example.temperate.service.user.aiinference.api.impl.ApiInferenceLifecycleServiceImpl;
import com.example.temperate.service.user.aiinference.concurrency.AiInferenceConcurrencyPermit;
import com.example.temperate.service.user.aiinference.concurrency.AiInferenceConcurrencyService;
import com.example.temperate.service.user.apichat.billing.ApiChatBillingService;
import com.example.temperate.service.user.apichat.impl.ApiChatCompletionServiceImpl;
import com.example.temperate.service.user.apichat.provider.ApiChatProviderAdapter;
import com.example.temperate.service.user.apichat.provider.ApiChatProviderAdapterRegistry;
import com.example.temperate.service.user.apichat.upstream.ApiChatSseParser;
import com.example.temperate.service.user.apichat.upstream.ApiChatSseParser.Normalization;
import com.example.temperate.service.user.apichat.upstream.ApiChatSseParser.ParsedChunk;
import com.example.temperate.service.user.apichat.upstream.ApiChatSseParser.ParsedEvent;
import com.example.temperate.service.user.apichat.upstream.ApiChatUpstreamClient;
import com.example.temperate.service.user.apichat.upstream.ApiChatUpstreamJson;
import com.example.temperate.service.user.apichat.upstream.ApiChatUpstreamStream;
import com.example.temperate.service.user.apichat.upstream.impl.ApiChatJsonParserImpl;
import com.example.temperate.service.user.apichat.diagnostic.impl.ApiChatStreamDiagnosticServiceImpl;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatProtocolViolation;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatProtocolViolationException;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 该测试是来约束公开流的最终 Usage、结算失败保留 RESERVED、系统退款和并发租约释放边界。
 */
final class ApiChatCompletionServiceImplTest {

    private static final String OUTPUT_FRAME = "{\"chunk\":1}";
    private static final String COMBINED_CHOICES_FRAME =
            "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}";
    private static final String USAGE_FRAME =
            "{\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}";

    @Test
    void settlesOnlyAfterUniqueFinalUsageAndDone() {
        Fixture fixture = fixture(Flux.just("output", "usage", "done"));

        List<String> output = fixture.service().stream(fixture.principal(), fixture.request())
                .collectList()
                .block();

        assertThat(output).containsExactly("{\"chunk\":1}", "[DONE]");
        verify(fixture.billingService()).settle(
                fixture.reservation(), new ApiInferenceUsage(12, 3, 2), "STOP");
        verify(fixture.billingService(), never()).refundSystemFailure(any(), any());
        verify(fixture.concurrencyService()).release(fixture.permit());
    }

    @Test
    void combinedTerminalUsageIsNormalizedAndForwardedWhenRequested() {
        Fixture fixture = fixture(Flux.just("combined", "done"), true);

        List<String> output = fixture.service().stream(fixture.principal(), fixture.request())
                .collectList()
                .block();

        assertThat(output).containsExactly(
                COMBINED_CHOICES_FRAME,
                USAGE_FRAME,
                "[DONE]");
        verify(fixture.billingService(), times(1)).settle(
                fixture.reservation(), new ApiInferenceUsage(12, 3, 2), "STOP");
        verify(fixture.billingService(), never()).refundSystemFailure(any(), any());
        verify(fixture.concurrencyService(), times(1)).release(fixture.permit());
        assertThat(fixture.meterRegistry()
                .get("api.chat.completion")
                .tag("result", "combined_usage_normalized")
                .counter()
                .count())
                .isEqualTo(1.0d);
    }

    @Test
    void combinedTerminalUsageIsKeptForSettlementButHiddenWhenNotRequested() {
        Fixture fixture = fixture(Flux.just("combined", "done"), false);

        List<String> output = fixture.service().stream(fixture.principal(), fixture.request())
                .collectList()
                .block();

        assertThat(output).containsExactly(COMBINED_CHOICES_FRAME, "[DONE]");
        verify(fixture.billingService(), times(1)).settle(
                fixture.reservation(), new ApiInferenceUsage(12, 3, 2), "STOP");
        verify(fixture.concurrencyService(), times(1)).release(fixture.permit());
    }

    @Test
    void rejectsSecondRealUsageAsDuplicateUsage() {
        Fixture fixture = fixture(Flux.just("usage", "usage", "done"));

        assertThatThrownBy(() -> fixture.service()
                .stream(fixture.principal(), fixture.request())
                .collectList()
                .block())
                .isInstanceOf(ApiChatException.class)
                .satisfies(failure -> assertProtocolViolation(
                        failure,
                        ApiChatProtocolViolation.DUPLICATE_USAGE));

        verify(fixture.billingService()).refundSystemFailure(
                fixture.reservation(), ApiChatErrorCode.UPSTREAM_PROTOCOL_ERROR.name());
        verify(fixture.billingService(), never()).settle(any(), any(), any());
        verify(fixture.concurrencyService(), times(1)).release(fixture.permit());
    }

    @Test
    void settlementRetryExhaustionKeepsReservationForRecovery() {
        Fixture fixture = fixture(Flux.just("output", "usage", "done"));
        doThrow(new IllegalStateException("database unavailable"))
                .when(fixture.billingService())
                .settle(any(), any(), any());

        List<String> output = fixture.service().stream(fixture.principal(), fixture.request())
                .collectList()
                .block();

        assertThat(output).hasSize(3);
        assertThat(output.get(0)).isEqualTo("{\"chunk\":1}");
        assertThat(output.get(1)).contains("infrastructure_unavailable");
        assertThat(output.get(2)).isEqualTo("[DONE]");
        verify(fixture.billingService(), times(3)).settle(any(), any(), any());
        verify(fixture.billingService(), never()).refundSystemFailure(any(), any());
        verify(fixture.concurrencyService()).release(fixture.permit());
    }

    @Test
    void rejectsOutputAfterUsageAndRefundsBecauseUsageIsNotFinal() {
        Fixture fixture = fixture(Flux.just("usage", "output"));

        assertThatThrownBy(() -> fixture.service()
                .stream(fixture.principal(), fixture.request())
                .collectList()
                .block())
                .isInstanceOf(ApiChatException.class)
                .satisfies(failure -> assertProtocolViolation(
                        failure,
                        ApiChatProtocolViolation.DATA_AFTER_USAGE));

        verify(fixture.billingService()).refundSystemFailure(
                fixture.reservation(), ApiChatErrorCode.UPSTREAM_PROTOCOL_ERROR.name());
        verify(fixture.billingService(), never()).settle(any(), any(), any());
        verify(fixture.concurrencyService()).release(fixture.permit());
    }

    @Test
    void combinedUsageWithoutRealDoneRefundsAndReturnsControlledStreamError() {
        Fixture fixture = fixture(Flux.just("combined"));

        List<String> output = fixture.service().stream(fixture.principal(), fixture.request())
                .collectList()
                .block();

        assertThat(output).hasSize(3);
        assertThat(output.get(0)).isEqualTo(COMBINED_CHOICES_FRAME);
        assertThat(output.get(1)).contains("upstream_protocol_error");
        assertThat(output.get(2)).isEqualTo("[DONE]");
        verify(fixture.billingService()).refundSystemFailure(
                fixture.reservation(), ApiChatErrorCode.UPSTREAM_PROTOCOL_ERROR.name());
        verify(fixture.billingService(), never()).settle(any(), any(), any());
        verify(fixture.concurrencyService(), times(1)).release(fixture.permit());
    }

    @Test
    void doneWithoutUsageIsRejectedBeforeSettlement() {
        Fixture fixture = fixture(Flux.just("done"));

        assertThatThrownBy(() -> fixture.service()
                .stream(fixture.principal(), fixture.request())
                .collectList()
                .block())
                .isInstanceOf(ApiChatException.class)
                .satisfies(failure -> assertProtocolViolation(
                        failure,
                        ApiChatProtocolViolation.DONE_WITHOUT_USAGE));

        verify(fixture.billingService()).refundSystemFailure(
                fixture.reservation(), ApiChatErrorCode.UPSTREAM_PROTOCOL_ERROR.name());
        verify(fixture.billingService(), never()).settle(any(), any(), any());
        verify(fixture.concurrencyService(), times(1)).release(fixture.permit());
    }

    @Test
    void nonStreamingChatSettlesUsageBeforeReturningTheOriginalJson() {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode raw = objectMapper.createObjectNode();
        raw.put("model", "gpt-test");
        raw.putArray("messages").addObject()
                .put("role", "user").put("content", "hello");
        raw.put("stream", false);
        AiModelCacheEntry model = new AiModelCacheEntry(
                23L, "gpt-test", "openai", null, null, List.of(),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                8_192, 512,
                List.of(AiModelCapabilityCode.CHAT_COMPLETIONS));
        ValidatedApiChatRequest validated = ValidatedApiChatRequest.openAiEnhanced(
                model, 128, 32, false, false, raw);
        ApiKeyPrincipal principal = new ApiKeyPrincipal(
                11L, 17L, new byte[32], "B".repeat(43), Set.of(23L));
        ApiChatRequestValidator validator = mock(ApiChatRequestValidator.class);
        when(validator.validate(principal, raw)).thenReturn(validated);
        ApiChatProviderAdapter adapter = mock(ApiChatProviderAdapter.class);
        when(adapter.type()).thenReturn(AiModelProvider.OPENAI);
        ObjectNode payload = raw.deepCopy();
        when(adapter.adapt(validated)).thenReturn(payload);
        ApiChatProviderAdapterRegistry registry =
                new ApiChatProviderAdapterRegistry(Map.of("openai", adapter));

        AiInferenceConcurrencyService concurrency = mock(
                AiInferenceConcurrencyService.class);
        AiInferenceConcurrencyPermit permit = new AiInferenceConcurrencyPermit(
                HmacIdentifier.fromProtectedValue("A".repeat(43)),
                HmacIdentifier.fromProtectedValue("B".repeat(43)),
                "owner", (short) 1);
        when(concurrency.tryAcquireApiKey(17L, "B".repeat(43), (short) 1))
                .thenReturn(new AiInferenceConcurrencyService.AcquireResult(
                        AiInferenceConcurrencyService.Result.ACQUIRED, permit));
        ApiChatBillingService billing = mock(ApiChatBillingService.class);
        ApiInferenceReservation reservation = new ApiInferenceReservation(
                29L, 17L, 11L, 2L, 32L,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                ApiInferenceProtocol.CHAT_COMPLETIONS);
        when(billing.reserve(any(ApiKeyPrincipal.class),
                any(ApiInferenceExecutionRequest.class))).thenReturn(reservation);
        ObjectNode upstreamBody = objectMapper.createObjectNode();
        upstreamBody.put("object", "chat.completion");
        upstreamBody.putArray("choices").addObject()
                .put("index", 0)
                .putObject("message").put("role", "assistant").put("content", "ok");
        ((ObjectNode) upstreamBody.path("choices").get(0)).put("finish_reason", "stop");
        upstreamBody.putObject("usage")
                .put("prompt_tokens", 12)
                .put("completion_tokens", 3)
                .put("total_tokens", 15);
        ApiChatUpstreamClient upstream = new ApiChatUpstreamClient() {
            @Override
            public Mono<ApiChatUpstreamStream> stream(
                    ObjectNode ignored,
                    ApiInferenceUpstreamRequest upstreamRequest) {
                return Mono.error(new AssertionError(
                        "The JSON request must not open an SSE upstream."));
            }

            @Override
            public Mono<ApiChatUpstreamJson> create(
                    ObjectNode ignored,
                    ApiInferenceUpstreamRequest upstreamRequest) {
                return Mono.just(new ApiChatUpstreamJson(
                        upstreamBody, ApiInferenceUpstreamHeaders.empty()));
            }
        };
        ApiKeyProperties diagnosticProperties = new ApiKeyProperties();
        diagnosticProperties.getStreamDiagnostics().setEnabled(false);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ApiChatCompletionService service = new ApiChatCompletionServiceImpl(
                validator,
                new ApiInferenceLifecycleServiceImpl(
                        concurrency, billing, Runnable::run, meterRegistry),
                registry,
                upstream,
                mock(ApiChatSseParser.class),
                new ApiChatJsonParserImpl(),
                objectMapper,
                meterRegistry,
                new ApiChatStreamDiagnosticServiceImpl(diagnosticProperties));

        ApiChatCompletionCreation.Json creation =
                (ApiChatCompletionCreation.Json) service.create(principal, raw, null);
        var returned = creation.response().block();

        assertThat(returned.body()).isSameAs(upstreamBody);
        verify(billing).settle(
                reservation, new ApiInferenceUsage(12, 3, 0), "STOP");
        verify(concurrency).release(permit);
    }

    private static Fixture fixture(Flux<String> upstreamData) {
        return fixture(upstreamData, false);
    }

    private static Fixture fixture(
            Flux<String> upstreamData,
            boolean includeUsage) {
        ApiChatRequest request = new ApiChatRequest(
                "gpt-test",
                List.of(),
                JsonNodeFactory.instance.booleanNode(true),
                new ApiChatRequest.StreamOptions(
                        JsonNodeFactory.instance.booleanNode(includeUsage)),
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
        AiModelCacheEntry model = new AiModelCacheEntry(
                23L,
                "gpt-test",
                "openai",
                null,
                null,
                List.of(),
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                8_192,
                512,
                List.of(AiModelCapabilityCode.CHAT_COMPLETIONS));
        ValidatedApiChatRequest validated =
                new ValidatedApiChatRequest(request, model, 128, 32, includeUsage);
        ApiKeyPrincipal principal = new ApiKeyPrincipal(
                11L,
                17L,
                new byte[32],
                "B".repeat(43),
                Set.of(23L));

        ApiChatRequestValidator validator = mock(ApiChatRequestValidator.class);
        when(validator.validate(principal, request)).thenReturn(validated);
        ApiChatProviderAdapter adapter = mock(ApiChatProviderAdapter.class);
        when(adapter.type()).thenReturn(AiModelProvider.OPENAI);
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        when(adapter.adapt(validated)).thenReturn(payload);
        ApiChatProviderAdapterRegistry registry =
                new ApiChatProviderAdapterRegistry(Map.of("openai", adapter));

        AiInferenceConcurrencyService concurrency =
                mock(AiInferenceConcurrencyService.class);
        AiInferenceConcurrencyPermit permit = new AiInferenceConcurrencyPermit(
                HmacIdentifier.fromProtectedValue("A".repeat(43)),
                HmacIdentifier.fromProtectedValue("B".repeat(43)),
                "owner",
                (short) 1);
        when(concurrency.tryAcquireApiKey(17L, "B".repeat(43), (short) 1))
                .thenReturn(new AiInferenceConcurrencyService.AcquireResult(
                        AiInferenceConcurrencyService.Result.ACQUIRED,
                        permit));

        ApiChatBillingService billing = mock(ApiChatBillingService.class);
        ApiInferenceReservation reservation = new ApiInferenceReservation(
                29L,
                17L,
                11L,
                2L,
                32L,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                ApiInferenceProtocol.CHAT_COMPLETIONS);
        when(billing.reserve(any(ApiKeyPrincipal.class), any(ApiInferenceExecutionRequest.class)))
                .thenReturn(reservation);
        ApiChatUpstreamClient upstream = new ApiChatUpstreamClient() {
            @Override
            public Mono<ApiChatUpstreamStream> stream(
                    ObjectNode ignored,
                    ApiInferenceUpstreamRequest upstreamRequest) {
                return Mono.just(new ApiChatUpstreamStream(
                        upstreamData, ApiInferenceUpstreamHeaders.empty()));
            }

            @Override
            public Mono<ApiChatUpstreamJson> create(
                    ObjectNode ignored,
                    ApiInferenceUpstreamRequest upstreamRequest) {
                return Mono.error(new AssertionError(
                        "The streaming fixture must not call the JSON upstream."));
            }
        };
        ApiChatSseParser parser = data -> switch (data) {
            case "output" -> event(new ParsedChunk(
                    OUTPUT_FRAME, null, false, true, 3, "stop"));
            case "usage" -> event(usageChunk());
            case "combined" -> new ParsedEvent(
                    List.of(
                            new ParsedChunk(
                                    COMBINED_CHOICES_FRAME,
                                    null,
                                    false,
                                    false,
                                    0,
                                    "stop"),
                            usageChunk()),
                    Normalization.COMBINED_CHOICES_AND_USAGE);
            case "done" -> event(new ParsedChunk(
                    "[DONE]", null, true, false, 0, null));
            default -> throw new AssertionError("Unexpected upstream fixture data");
        };
        ApiKeyProperties diagnosticProperties = new ApiKeyProperties();
        diagnosticProperties.getStreamDiagnostics().setEnabled(false);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ApiChatCompletionService service = new ApiChatCompletionServiceImpl(
                validator,
                new ApiInferenceLifecycleServiceImpl(
                        concurrency, billing, Runnable::run, meterRegistry),
                registry,
                upstream,
                parser,
                new ApiChatJsonParserImpl(),
                new ObjectMapper(),
                meterRegistry,
                new ApiChatStreamDiagnosticServiceImpl(diagnosticProperties));
        return new Fixture(
                service,
                principal,
                request,
                billing,
                concurrency,
                reservation,
                permit,
                meterRegistry);
    }

    private static ParsedEvent event(ParsedChunk chunk) {
        return new ParsedEvent(List.of(chunk), Normalization.NONE);
    }

    private static ParsedChunk usageChunk() {
        return new ParsedChunk(
                USAGE_FRAME,
                "{}",
                new ApiInferenceUsage(12, 3, 2),
                false,
                false,
                0,
                null,
                true);
    }

    private static void assertProtocolViolation(
            Throwable failure,
            ApiChatProtocolViolation expected) {
        ApiChatException controlled = (ApiChatException) failure;
        assertThat(controlled.getCause())
                .isInstanceOf(ApiChatProtocolViolationException.class);
        assertThat(((ApiChatProtocolViolationException) controlled.getCause()).violation())
                .isEqualTo(expected);
    }

    private record Fixture(
            ApiChatCompletionService service,
            ApiKeyPrincipal principal,
            ApiChatRequest request,
            ApiChatBillingService billingService,
            AiInferenceConcurrencyService concurrencyService,
            ApiInferenceReservation reservation,
            AiInferenceConcurrencyPermit permit,
            SimpleMeterRegistry meterRegistry) {
    }
}
