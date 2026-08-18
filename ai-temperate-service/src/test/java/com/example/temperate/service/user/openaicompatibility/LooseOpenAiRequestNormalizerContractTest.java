package com.example.temperate.service.user.openaicompatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.example.temperate.service.user.openaicompatibility.impl.ChatLooseOpenAiRequestNormalizerImpl;
import com.example.temperate.service.user.openaicompatibility.impl.ResponsesLooseOpenAiRequestNormalizerImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来锁定 Chat 与 Responses 宽松规范化的字段保留、安全覆盖、能力门控和 Registry 唯一性契约。
 */
final class LooseOpenAiRequestNormalizerContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void dropsWorkBuddyDialectFieldsButPreservesComplexKnownChatContent() throws Exception {
        ApiKeyProperties properties = properties();
        ChatLooseOpenAiRequestNormalizerImpl normalizer =
                new ChatLooseOpenAiRequestNormalizerImpl(properties, objectMapper);
        ObjectNode raw = object("""
                {
                  "model":"GPT-Test",
                  "messages":[{
                    "role":"user",
                    "agent":"workbuddy",
                    "content":[{"type":"text","text":"hello","vendor_hint":true}],
                    "reasoning_content":{"summary":"keep-structure"}
                  }],
                  "workbuddy_session":"drop-me",
                  "max_tokens":64,
                  "max_completion_tokens":96
                }
                """);

        LooseOpenAiRequestNormalization normalized = normalizer.normalize(
                new LooseOpenAiRequestContext(
                        raw, model(List.of(AiModelCapabilityCode.CHAT_COMPLETIONS)),
                        OpenAiCompatibilityProtocol.CHAT_COMPLETIONS));

        assertThat(normalized.payloadMode())
                .isEqualTo(OpenAiRequestPayloadMode.LOOSE_NORMALIZED);
        assertThat(normalized.stream()).isFalse();
        assertThat(normalized.effectiveMaxOutputTokens()).isEqualTo(96L);
        assertThat(normalized.droppedFieldCount()).isEqualTo(2);
        assertThat(normalized.normalizedPayload().has("workbuddy_session")).isFalse();
        assertThat(normalized.normalizedPayload().at("/messages/0").has("agent")).isFalse();
        assertThat(normalized.normalizedPayload().at("/messages/0/content/0/vendor_hint")
                .booleanValue()).isTrue();
        assertThat(normalized.normalizedPayload().has("max_tokens")).isFalse();
        assertThat(normalized.normalizedPayload().path("store").booleanValue()).isFalse();
    }

    @Test
    void controlledChatPassthroughKeepsUnknownBodyFieldsButCannotOverrideSafetyFields()
            throws Exception {
        ApiKeyProperties properties = properties();
        properties.getOpenAiCompatibility().setPassthroughModels(List.of("gpt-test"));
        ChatLooseOpenAiRequestNormalizerImpl normalizer =
                new ChatLooseOpenAiRequestNormalizerImpl(properties, objectMapper);
        ObjectNode raw = object("""
                {"model":"GPT-TEST","messages":[{"role":"user","content":"hello",
                 "agent":"keep-in-passthrough"}],"stream":true,"store":true,
                 "max_completion_tokens":999999,"vendor_extension":{"enabled":true},
                 "modalities":["audio"],"audio":{"voice":"alloy"}}
                """);

        LooseOpenAiRequestNormalization normalized = normalizer.normalize(
                new LooseOpenAiRequestContext(
                        raw, model(List.of(AiModelCapabilityCode.CHAT_COMPLETIONS)),
                        OpenAiCompatibilityProtocol.CHAT_COMPLETIONS));

        ObjectNode payload = normalized.normalizedPayload();
        assertThat(normalized.payloadMode())
                .isEqualTo(OpenAiRequestPayloadMode.CONTROLLED_PASSTHROUGH);
        assertThat(payload.path("vendor_extension").path("enabled").booleanValue()).isTrue();
        assertThat(payload.at("/messages/0/agent").textValue())
                .isEqualTo("keep-in-passthrough");
        assertThat(payload.path("model").textValue()).isEqualTo("gpt-test");
        assertThat(payload.path("store").booleanValue()).isFalse();
        assertThat(payload.path("max_completion_tokens").longValue()).isEqualTo(512L);
        assertThat(payload.has("modalities")).isFalse();
        assertThat(payload.has("audio")).isFalse();
    }

    @Test
    void leavesMissingMessageContentAbsentForTheFinalUpstreamToJudge() throws Exception {
        ChatLooseOpenAiRequestNormalizerImpl normalizer =
                new ChatLooseOpenAiRequestNormalizerImpl(properties(), objectMapper);
        ObjectNode raw = object("""
                {"model":"gpt-test","messages":[{"role":"assistant",
                 "tool_calls":[{"id":"call_1","type":"function",
                 "function":{"name":"lookup","arguments":"{}"}}]}]}
                """);

        LooseOpenAiRequestNormalization normalized = normalizer.normalize(
                new LooseOpenAiRequestContext(
                        raw, model(List.of(AiModelCapabilityCode.CHAT_COMPLETIONS)),
                        OpenAiCompatibilityProtocol.CHAT_COMPLETIONS));

        assertThat(normalized.normalizedPayload().at("/messages/0").has("content"))
                .isFalse();
        assertThat(normalized.normalizedPayload().at("/messages/0/tool_calls/0/id")
                .textValue()).isEqualTo("call_1");
    }

    @Test
    void normalizesCodexResponsesStateAndFiltersHostedTools() throws Exception {
        ApiKeyProperties properties = properties();
        ResponsesLooseOpenAiRequestNormalizerImpl normalizer =
                new ResponsesLooseOpenAiRequestNormalizerImpl(properties, objectMapper);
        ObjectNode raw = object("""
                {
                  "model":"gpt-test",
                  "input":[{"type":"message","role":"user","content":[
                    {"type":"input_text","text":"hello","client_hint":"keep"}]}],
                  "client_metadata":{"agent":"codex"},
                  "codex_preview":true,
                  "background":true,
                  "previous_response_id":"resp_old",
                  "conversation":"conv_old",
                  "tools":[
                    {"type":"function","name":"lookup","parameters":{"type":"object"}},
                    {"type":"file_search","vector_store_ids":["vs_1"]},
                    {"type":"web_search"}
                  ]
                }
                """);

        LooseOpenAiRequestNormalization normalized = normalizer.normalize(
                new LooseOpenAiRequestContext(
                        raw,
                        model(List.of(
                                AiModelCapabilityCode.RESPONSES,
                                AiModelCapabilityCode.WEB_SEARCH)),
                        OpenAiCompatibilityProtocol.RESPONSES));

        ObjectNode payload = normalized.normalizedPayload();
        assertThat(payload.path("client_metadata").path("agent").textValue())
                .isEqualTo("codex");
        assertThat(payload.has("codex_preview")).isFalse();
        assertThat(payload.has("background")).isFalse();
        assertThat(payload.has("previous_response_id")).isFalse();
        assertThat(payload.has("conversation")).isFalse();
        assertThat(payload.path("store").booleanValue()).isFalse();
        assertThat(payload.path("tools").size()).isEqualTo(2);
        assertThat(payload.at("/input/0/content/0/client_hint").textValue())
                .isEqualTo("keep");
    }

    @Test
    void controlledResponsesPassthroughKeepsUnknownFieldsButRemainsStateless()
            throws Exception {
        ApiKeyProperties properties = properties();
        properties.getOpenAiCompatibility().setPassthroughModels(List.of("GPT-TEST"));
        ResponsesLooseOpenAiRequestNormalizerImpl normalizer =
                new ResponsesLooseOpenAiRequestNormalizerImpl(properties, objectMapper);
        ObjectNode raw = object("""
                {"model":"gpt-test","input":"hello","store":true,
                 "background":true,"previous_response_id":"resp_old",
                 "conversation":"conv_old","provider_extension":{"cache":"private"}}
                """);

        LooseOpenAiRequestNormalization normalized = normalizer.normalize(
                new LooseOpenAiRequestContext(
                        raw, model(List.of(AiModelCapabilityCode.RESPONSES)),
                        OpenAiCompatibilityProtocol.RESPONSES));

        ObjectNode payload = normalized.normalizedPayload();
        assertThat(normalized.payloadMode())
                .isEqualTo(OpenAiRequestPayloadMode.CONTROLLED_PASSTHROUGH);
        assertThat(payload.at("/provider_extension/cache").textValue())
                .isEqualTo("private");
        assertThat(payload.path("store").booleanValue()).isFalse();
        assertThat(payload.has("background")).isFalse();
        assertThat(payload.has("previous_response_id")).isFalse();
        assertThat(payload.has("conversation")).isFalse();
    }

    @Test
    void doesNotMistakeFunctionOutputJsonForMediaInput() throws Exception {
        ResponsesLooseOpenAiRequestNormalizerImpl normalizer =
                new ResponsesLooseOpenAiRequestNormalizerImpl(properties(), objectMapper);
        ObjectNode raw = object("""
                {"model":"gpt-test","input":[{"type":"function_call_output",
                 "call_id":"call_1","output":{"type":"file","name":"report"}}]}
                """);

        LooseOpenAiRequestNormalization normalized = normalizer.normalize(
                new LooseOpenAiRequestContext(
                        raw, model(List.of(AiModelCapabilityCode.RESPONSES)),
                        OpenAiCompatibilityProtocol.RESPONSES));

        assertThat(normalized.normalizedPayload().at("/input/0/output/type").textValue())
                .isEqualTo("file");
    }

    @Test
    void removesWebSearchWhenTheModelDoesNotDeclareItsCapability() throws Exception {
        ResponsesLooseOpenAiRequestNormalizerImpl normalizer =
                new ResponsesLooseOpenAiRequestNormalizerImpl(properties(), objectMapper);
        ObjectNode raw = object("""
                {"model":"gpt-test","input":"hello","tools":[
                 {"type":"web_search"},{"type":"code_interpreter"}]}
                """);

        LooseOpenAiRequestNormalization normalized = normalizer.normalize(
                new LooseOpenAiRequestContext(
                        raw, model(List.of(AiModelCapabilityCode.RESPONSES)),
                        OpenAiCompatibilityProtocol.RESPONSES));

        assertThat(normalized.normalizedPayload().has("tools")).isFalse();
        assertThat(normalized.droppedFieldCount()).isEqualTo(2);
    }

    @Test
    void rejectsRecognizedMediaWithoutCapabilityAndAlwaysRejectsInputFile() throws Exception {
        ResponsesLooseOpenAiRequestNormalizerImpl normalizer =
                new ResponsesLooseOpenAiRequestNormalizerImpl(properties(), objectMapper);
        AiModelCacheEntry responseModel = model(List.of(AiModelCapabilityCode.RESPONSES));
        ObjectNode image = object("""
                {"model":"gpt-test","input":[{"role":"user","content":[
                 {"type":"input_image","image_url":"https://example.invalid/a.png"}]}]}
                """);
        ObjectNode file = object("""
                {"model":"gpt-test","input":[{"role":"user","content":[
                 {"type":"input_file","file_id":"file_1"}]}]}
                """);

        assertThatThrownBy(() -> normalizer.normalize(new LooseOpenAiRequestContext(
                image, responseModel, OpenAiCompatibilityProtocol.RESPONSES)))
                .isInstanceOf(ApiChatException.class)
                .hasMessageContaining("IMAGE_INPUT");
        LooseOpenAiRequestNormalization imageAllowed = normalizer.normalize(
                new LooseOpenAiRequestContext(
                        image,
                        model(List.of(
                                AiModelCapabilityCode.RESPONSES,
                                AiModelCapabilityCode.IMAGE_INPUT)),
                        OpenAiCompatibilityProtocol.RESPONSES));
        assertThat(imageAllowed.normalizedPayload().at("/input/0/content/0/type")
                .textValue()).isEqualTo("input_image");
        assertThatThrownBy(() -> normalizer.normalize(new LooseOpenAiRequestContext(
                file, responseModel, OpenAiCompatibilityProtocol.RESPONSES)))
                .isInstanceOf(ApiChatException.class)
                .hasMessageContaining("File input");
    }

    @Test
    void registryRejectsDuplicateProtocolAndReturnsRegisteredStrategy() {
        LooseOpenAiRequestNormalizer chat = new StubNormalizer(
                OpenAiCompatibilityProtocol.CHAT_COMPLETIONS);
        LooseOpenAiRequestNormalizer responses = new StubNormalizer(
                OpenAiCompatibilityProtocol.RESPONSES);

        LooseOpenAiRequestNormalizerRegistry registry =
                new LooseOpenAiRequestNormalizerRegistry(Map.of(
                        "chat", chat, "responses", responses));

        assertThat(registry.getRequired(OpenAiCompatibilityProtocol.RESPONSES))
                .isSameAs(responses);
        assertThatThrownBy(() -> new LooseOpenAiRequestNormalizerRegistry(Map.of(
                "chatOne", chat,
                "chatTwo", new StubNormalizer(OpenAiCompatibilityProtocol.CHAT_COMPLETIONS))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
        LooseOpenAiRequestNormalizerRegistry incomplete =
                new LooseOpenAiRequestNormalizerRegistry(Map.of("chat", chat));
        assertThatThrownBy(() -> incomplete.getRequired(
                OpenAiCompatibilityProtocol.RESPONSES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported");
    }

    private ApiKeyProperties properties() {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.getOpenAiCompatibility().setEnabled(true);
        return properties;
    }

    private ObjectNode object(String json) throws Exception {
        return (ObjectNode) objectMapper.readTree(json);
    }

    private static AiModelCacheEntry model(List<AiModelCapabilityCode> capabilities) {
        return new AiModelCacheEntry(
                7L, "gpt-test", "openai", "test", "", List.of(),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                8_192L, 512L, capabilities);
    }

    private record StubNormalizer(OpenAiCompatibilityProtocol protocol)
            implements LooseOpenAiRequestNormalizer {

        @Override
        public LooseOpenAiRequestNormalization normalize(LooseOpenAiRequestContext context) {
            throw new UnsupportedOperationException("Registry test does not normalize payloads");
        }
    }
}
