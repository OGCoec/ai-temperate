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
import com.example.temperate.service.user.aiinference.concurrency.AiInferenceConcurrencyPermit;
import com.example.temperate.service.user.aiinference.concurrency.AiInferenceConcurrencyService;
import com.example.temperate.service.user.apichat.billing.ApiChatBillingService;
import com.example.temperate.service.user.apichat.billing.ApiChatBillingService.Reservation;
import com.example.temperate.service.user.apichat.billing.ApiChatBillingService.Usage;
import com.example.temperate.service.user.apichat.impl.ApiChatCompletionServiceImpl;
import com.example.temperate.service.user.apichat.provider.ApiChatProviderAdapter;
import com.example.temperate.service.user.apichat.provider.ApiChatProviderAdapterRegistry;
import com.example.temperate.service.user.apichat.upstream.ApiChatSseParser;
import com.example.temperate.service.user.apichat.upstream.ApiChatSseParser.ParsedChunk;
import com.example.temperate.service.user.apichat.upstream.ApiChatUpstreamClient;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
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

/**
 * 该测试是来约束公开流的最终 Usage、结算失败保留 RESERVED、系统退款和并发租约释放边界。
 */
final class ApiChatCompletionServiceImplTest {

    @Test
    void settlesOnlyAfterUniqueFinalUsageAndDone() {
        Fixture fixture = fixture(Flux.just("output", "usage", "done"));

        List<String> output = fixture.service().stream(fixture.principal(), fixture.request())
                .collectList()
                .block();

        assertThat(output).containsExactly("{\"chunk\":1}", "[DONE]");
        verify(fixture.billingService()).settle(
                fixture.reservation(), new Usage(12, 3, 2), "STOP");
        verify(fixture.billingService(), never()).refundSystemFailure(any(), any());
        verify(fixture.concurrencyService()).release(fixture.permit());
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
                .isInstanceOf(ApiChatException.class);

        verify(fixture.billingService()).refundSystemFailure(
                fixture.reservation(), ApiChatErrorCode.UPSTREAM_PROTOCOL_ERROR.name());
        verify(fixture.billingService(), never()).settle(any(), any(), any());
        verify(fixture.concurrencyService()).release(fixture.permit());
    }

    private static Fixture fixture(Flux<String> upstreamData) {
        ApiChatRequest request = new ApiChatRequest(
                "gpt-test",
                List.of(),
                JsonNodeFactory.instance.booleanNode(true),
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
                new ValidatedApiChatRequest(request, model, 128, 32, false);
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
        Reservation reservation = new Reservation(
                29L,
                17L,
                11L,
                2L,
                32L,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE);
        when(billing.reserve(principal, validated)).thenReturn(reservation);
        ApiChatUpstreamClient upstream = ignored -> upstreamData;
        ApiChatSseParser parser = data -> switch (data) {
            case "output" -> new ParsedChunk(
                    "{\"chunk\":1}", null, false, true, 3, "stop", false);
            case "usage" -> new ParsedChunk(
                    "{\"choices\":[],\"usage\":{}}",
                    new Usage(12, 3, 2),
                    false,
                    false,
                    0,
                    null,
                    true);
            case "done" -> new ParsedChunk(
                    "[DONE]", null, true, false, 0, null, false);
            default -> throw new AssertionError("Unexpected upstream fixture data");
        };
        ApiChatCompletionService service = new ApiChatCompletionServiceImpl(
                validator,
                concurrency,
                billing,
                registry,
                upstream,
                parser,
                new ObjectMapper(),
                Runnable::run,
                new SimpleMeterRegistry());
        return new Fixture(
                service,
                principal,
                request,
                billing,
                concurrency,
                reservation,
                permit);
    }

    private record Fixture(
            ApiChatCompletionService service,
            ApiKeyPrincipal principal,
            ApiChatRequest request,
            ApiChatBillingService billingService,
            AiInferenceConcurrencyService concurrencyService,
            Reservation reservation,
            AiInferenceConcurrencyPermit permit) {
    }
}
