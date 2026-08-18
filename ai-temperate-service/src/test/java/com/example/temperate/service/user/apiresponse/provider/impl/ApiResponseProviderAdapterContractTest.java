package com.example.temperate.service.user.apiresponse.provider.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apiresponse.ValidatedApiResponseRequest;
import com.example.temperate.service.user.apiresponse.impl.ApiResponsePayloadFactoryImpl;
import com.example.temperate.service.user.apiresponse.provider.ApiResponseProviderAdapter;
import com.example.temperate.service.user.openaicompatibility.OpenAiRequestPayloadMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来锁定 Responses 厂商 Adapter 在普通宽松、受控透传和模型能力不匹配时的字段处理边界。
 */
final class ApiResponseProviderAdapterContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void filtersKnownUnsupportedFieldsAndOnlyPassthroughKeepsUnknownExtensions()
            throws Exception {
        ObjectNode payload = (ObjectNode) objectMapper.readTree("""
                {"model":"xai-test","input":"hello","stream":false,"store":false,
                 "max_output_tokens":128,"client_metadata":{"agent":"codex"},
                 "vendor_extension":{"priority":7}}
                """);
        for (AiModelProvider provider : List.of(
                AiModelProvider.XAI, AiModelProvider.ANTHROPIC, AiModelProvider.GOOGLE)) {
            ApiResponseProviderAdapter adapter = adapter(provider);
            ObjectNode loose = adapter.adapt(validated(
                    payload, provider, OpenAiRequestPayloadMode.LOOSE_NORMALIZED,
                    List.of(AiModelCapabilityCode.RESPONSES)));
            ObjectNode passthrough = adapter.adapt(validated(
                    payload, provider, OpenAiRequestPayloadMode.CONTROLLED_PASSTHROUGH,
                    List.of(AiModelCapabilityCode.RESPONSES)));

            assertThat(loose.has("client_metadata")).as(provider.name()).isFalse();
            assertThat(loose.has("vendor_extension")).as(provider.name()).isFalse();
            assertThat(passthrough.has("client_metadata")).as(provider.name()).isFalse();
            assertThat(passthrough.at("/vendor_extension/priority").intValue())
                    .as(provider.name()).isEqualTo(7);
        }
    }

    @Test
    void rejectsProviderOrResponsesCapabilityMismatch() throws Exception {
        ObjectNode payload = (ObjectNode) objectMapper.readTree(
                "{\"model\":\"google-test\",\"input\":\"hello\"}");
        OpenAiApiResponseProviderAdapter adapter = new OpenAiApiResponseProviderAdapter(
                new ApiResponsePayloadFactoryImpl(objectMapper));

        assertThatThrownBy(() -> adapter.adapt(validated(
                payload, AiModelProvider.GOOGLE,
                OpenAiRequestPayloadMode.LOOSE_NORMALIZED,
                List.of(AiModelCapabilityCode.RESPONSES))))
                .isInstanceOf(ApiChatException.class);
        assertThatThrownBy(() -> adapter.adapt(validated(
                payload, AiModelProvider.OPENAI,
                OpenAiRequestPayloadMode.LOOSE_NORMALIZED,
                List.of(AiModelCapabilityCode.CHAT_COMPLETIONS))))
                .isInstanceOf(ApiChatException.class);
    }

    private static ValidatedApiResponseRequest validated(
            ObjectNode payload,
            AiModelProvider provider,
            OpenAiRequestPayloadMode mode,
            List<AiModelCapabilityCode> capabilities) {
        return ValidatedApiResponseRequest.compatible(
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
                        capabilities),
                128,
                16,
                false,
                payload,
                mode,
                0);
    }

    private ApiResponseProviderAdapter adapter(AiModelProvider provider) {
        ApiResponsePayloadFactoryImpl payloadFactory =
                new ApiResponsePayloadFactoryImpl(objectMapper);
        return switch (provider) {
            case OPENAI -> new OpenAiApiResponseProviderAdapter(payloadFactory);
            case XAI -> new XaiApiResponseProviderAdapter(payloadFactory);
            case ANTHROPIC -> new AnthropicApiResponseProviderAdapter(payloadFactory);
            case GOOGLE -> new GoogleApiResponseProviderAdapter(payloadFactory);
        };
    }
}
