package com.example.temperate.service.user.apichat.provider.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apichat.ApiChatRequest;
import com.example.temperate.service.user.apichat.ValidatedApiChatRequest;
import com.example.temperate.service.user.apichat.provider.ApiChatPayloadFactory;
import com.example.temperate.service.user.apichat.provider.ApiChatProviderAdapter;
import com.example.temperate.service.user.openaicompatibility.OpenAiRequestPayloadMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来锁定四厂商分别批准的 8317 顶层字段及其实际 JSON 类型，并阻止公共 DTO 扩展后未经适配器批准的字段自动透传。
 */
final class ApiChatProviderAdapterContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ApiChatPayloadFactory payloadFactory =
            new ApiChatPayloadFactoryImpl(objectMapper);

    @Test
    void everyProviderProducesTheApprovedOpenAiCompatibleJsonTypes()
            throws Exception {
        String longDescription = "x".repeat(7_125);
        ApiChatRequest request = objectMapper.readValue("""
                {
                  "model":"client-model",
                  "messages":[{"role":"user","content":[
                    {"type":"text","text":"hel"},{"type":"text","text":"lo"}]}],
                  "stream":true,
                  "reasoning_effort":"ultra",
                  "prompt_cache_key":"agent-session-1",
                  "store":false,
                  "service_tier":"flex",
                  "stream_options":{"include_usage":false},
                  "max_tokens":128,
                  "temperature":0.5,
                  "top_p":0.9,
                  "presence_penalty":0,
                  "frequency_penalty":0,
                  "stop":["END"],
                  "seed":7,
                  "n":1,
                  "tools":[{"type":"function","function":{"name":"weather","description":"%s","parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}}],
                  "tool_choice":{"type":"function","function":{"name":"weather"}},
                  "parallel_tool_calls":true
                }
                """.formatted(longDescription), ApiChatRequest.class);

        for (AiModelProvider provider : AiModelProvider.values()) {
            ObjectNode payload = adapter(provider, payloadFactory).adapt(
                    validated(request, provider));

            assertThat(payload.get("model").textValue())
                    .isEqualTo(provider.vendor() + "-test");
            assertThat(payload.get("messages").isArray()).isTrue();
            assertThat(payload.at("/messages/0/content").textValue()).isEqualTo("hello");
            assertThat(payload.get("stream").isBoolean()).isTrue();
            assertThat(payload.path("reasoning_effort").textValue()).isEqualTo("ultra");
            assertThat(payload.path("prompt_cache_key").textValue()).isEqualTo("agent-session-1");
            assertThat(payload.path("store").booleanValue()).isFalse();
            assertThat(payload.path("service_tier").textValue()).isEqualTo("flex");
            assertThat(payload.at("/stream_options/include_usage").booleanValue())
                    .isTrue();
            assertThat(payload.get("max_completion_tokens").isIntegralNumber())
                    .isTrue();
            assertThat(payload.has("max_tokens")).isFalse();
            assertThat(payload.get("temperature").isNumber()).isTrue();
            assertThat(payload.get("top_p").isNumber()).isTrue();
            assertThat(payload.get("seed").isIntegralNumber()).isTrue();
            assertThat(payload.get("n").isIntegralNumber()).isTrue();
            assertThat(payload.get("tools").isArray()).isTrue();
            assertThat(payload.at("/tools/0/function/description").textValue())
                    .isEqualTo(longDescription);
            assertThat(payload.at("/tools/0/function/parameters/properties/city/type")
                    .textValue()).isEqualTo("string");
            assertThat(payload.get("tool_choice").isObject()).isTrue();
            assertThat(payload.get("parallel_tool_calls").isBoolean()).isTrue();
        }
    }

    @Test
    void everyProviderRejectsAFactoryFieldItHasNotExplicitlyApproved()
            throws Exception {
        ApiChatRequest request = objectMapper.readValue("""
                {"model":"client-model","messages":[{"role":"user","content":"hello"}],"stream":true}
                """, ApiChatRequest.class);
        ApiChatPayloadFactory futureFactory = validated -> {
            ObjectNode payload = payloadFactory.create(validated);
            payload.put("future_field", true);
            return payload;
        };

        for (AiModelProvider provider : AiModelProvider.values()) {
            assertThatThrownBy(() -> adapter(provider, futureFactory)
                    .adapt(validated(request, provider)))
                    .as(provider.name())
                    .isInstanceOf(ApiChatException.class);
        }
    }

    @Test
    void looseModeFiltersUnsupportedKnownFieldsAndPassthroughKeepsUnknownExtensions()
            throws Exception {
        ObjectNode normalized = (ObjectNode) objectMapper.readTree("""
                {"model":"xai-test","messages":[{"role":"user","content":"hello"}],
                 "stream":false,"store":false,"max_completion_tokens":128,
                 "verbosity":"high","vendor_extension":{"mode":"fast"}}
                """);
        for (AiModelProvider provider : List.of(
                AiModelProvider.XAI, AiModelProvider.ANTHROPIC, AiModelProvider.GOOGLE)) {
            ApiChatProviderAdapter adapter = adapter(provider, payloadFactory);
            ObjectNode loose = adapter.adapt(compatible(
                    normalized, provider, OpenAiRequestPayloadMode.LOOSE_NORMALIZED));
            ObjectNode passthrough = adapter.adapt(compatible(
                    normalized, provider,
                    OpenAiRequestPayloadMode.CONTROLLED_PASSTHROUGH));

            assertThat(loose.has("verbosity")).as(provider.name()).isFalse();
            assertThat(loose.has("vendor_extension")).as(provider.name()).isFalse();
            assertThat(passthrough.has("verbosity")).as(provider.name()).isFalse();
            assertThat(passthrough.at("/vendor_extension/mode").textValue())
                    .as(provider.name()).isEqualTo("fast");
        }
    }

    @Test
    void providerRejectsAModelOwnedByAnotherVendor() throws Exception {
        ApiChatRequest request = objectMapper.readValue("""
                {"model":"client-model","messages":[{"role":"user","content":"hello"}],"stream":true}
                """, ApiChatRequest.class);

        assertThatThrownBy(() -> new OpenAiApiChatProviderAdapter(payloadFactory)
                .adapt(validated(request, AiModelProvider.GOOGLE)))
                .isInstanceOf(ApiChatException.class);
    }

    private static ApiChatProviderAdapter adapter(
            AiModelProvider provider,
            ApiChatPayloadFactory factory) {
        return switch (provider) {
            case OPENAI -> new OpenAiApiChatProviderAdapter(factory);
            case XAI -> new XaiApiChatProviderAdapter(factory);
            case ANTHROPIC -> new AnthropicApiChatProviderAdapter(factory);
            case GOOGLE -> new GoogleApiChatProviderAdapter(factory);
        };
    }

    private static ValidatedApiChatRequest validated(
            ApiChatRequest request,
            AiModelProvider provider) {
        return new ValidatedApiChatRequest(
                request,
                new AiModelCacheEntry(
                        provider.ordinal() + 1L,
                        provider.vendor() + "-test",
                        provider.vendor(),
                        "test",
                        null,
                        List.of(),
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        8_192,
                        1_024,
                        List.of(AiModelCapabilityCode.CHAT_COMPLETIONS)),
                128,
                16,
                false);
    }

    private static ValidatedApiChatRequest compatible(
            ObjectNode payload,
            AiModelProvider provider,
            OpenAiRequestPayloadMode mode) {
        return ValidatedApiChatRequest.compatible(
                new AiModelCacheEntry(
                        provider.ordinal() + 1L,
                        provider.vendor() + "-test",
                        provider.vendor(),
                        "test",
                        null,
                        List.of(),
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        8_192,
                        1_024,
                        List.of(AiModelCapabilityCode.CHAT_COMPLETIONS)),
                128,
                16,
                false,
                false,
                payload,
                mode,
                0);
    }
}
