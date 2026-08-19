package com.example.temperate.service.user.apiresponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheService;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheSnapshot;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.aiinference.api.ApiInferenceUsage;
import com.example.temperate.service.user.aiinference.sse.ApiInferenceSseDecoder;
import com.example.temperate.service.user.aiinference.sse.ApiInferenceSseEvent;
import com.example.temperate.service.user.apichat.ApiChatErrorCode;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.example.temperate.service.user.apiresponse.impl.ApiResponsePayloadFactoryImpl;
import com.example.temperate.service.user.apiresponse.impl.ApiResponseRequestValidatorImpl;
import com.example.temperate.service.user.apiresponse.provider.ApiResponseProviderAdapter;
import com.example.temperate.service.user.apiresponse.provider.ApiResponseProviderAdapterRegistry;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseJsonResult.Status;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseSseFrame.TerminalKind;
import com.example.temperate.service.user.apiresponse.upstream.impl.ApiResponseProtocolParserImpl;
import com.example.temperate.service.user.openaicompatibility.LooseOpenAiRequestNormalizerRegistry;
import com.example.temperate.service.user.openaicompatibility.OpenAiRequestPayloadMode;
import com.example.temperate.service.user.openaicompatibility.impl.ResponsesLooseOpenAiRequestNormalizerImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来锁定 Responses 严格回滚路径与所有厂商共享宽松路径、store=false、Usage 终态和原始 SSE 契约。
 */
final class ApiResponseContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void routesAllVendorsThroughTheLooseCompatibilityPath()
            throws Exception {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.getOpenAiCompatibility().setEnabled(true);
        ObjectNode raw = (ObjectNode) objectMapper.readTree("""
                {"model":"gpt-test","input":"hello","top_logprobs":2,
                 "text":{"format":{"type":"json_object"}}}
                """);
        ApiResponseRequestValidator openAi = new ApiResponseRequestValidatorImpl(
                cacheService(model(
                        List.of(AiModelCapabilityCode.RESPONSES), "openai")),
                properties,
                objectMapper,
                registry(properties));

        ValidatedApiResponseRequest enhanced = openAi.validate(principal(), raw);

        assertThat(enhanced.payloadMode())
                .isEqualTo(OpenAiRequestPayloadMode.LOOSE_NORMALIZED);
        assertThat(enhanced.normalizedPayload().path("store").booleanValue()).isFalse();
        ObjectNode enhancedPayload = new ApiResponsePayloadFactoryImpl(objectMapper)
                .create(enhanced);
        assertThat(enhancedPayload.path("top_logprobs").intValue()).isEqualTo(2);
        assertThat(enhancedPayload.at("/text/format/type").textValue())
                .isEqualTo("json_object");

        ApiResponseRequestValidator xai = new ApiResponseRequestValidatorImpl(
                cacheService(model(
                        List.of(AiModelCapabilityCode.RESPONSES), "xai")),
                properties,
                objectMapper,
                registry(properties));
        ObjectNode legacy = (ObjectNode) objectMapper.readTree(
                "{\"model\":\"gpt-test\",\"input\":\"hello\"}");

        assertThat(xai.validate(principal(), legacy).payloadMode())
                .isEqualTo(OpenAiRequestPayloadMode.LOOSE_NORMALIZED);
    }

    @Test
    void disabledCompatibilitySwitchRestoresLegacyStatefulRejection() throws Exception {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.getOpenAiCompatibility().setEnabled(false);
        ApiResponseRequestValidator validator = new ApiResponseRequestValidatorImpl(
                cacheService(model(List.of(AiModelCapabilityCode.RESPONSES))),
                properties,
                objectMapper,
                registry(properties));
        ObjectNode raw = (ObjectNode) objectMapper.readTree(
                "{\"model\":\"gpt-test\",\"input\":\"hello\",\"store\":true}");

        assertThatThrownBy(() -> validator.validate(principal(), raw))
                .isInstanceOf(ApiChatException.class)
                .hasMessageContaining("store=false");
    }

    @Test
    void validatesStatelessFunctionReplayAndForcesCanonicalPayload() throws Exception {
        ApiResponseRequest request = objectMapper.readValue("""
                {
                  "model":"gpt-test",
                  "input":[
                    {"role":"developer","content":"Use tools safely."},
                    {"role":"user","content":[{"type":"input_text","text":"weather"}]},
                    {"type":"reasoning","id":"rs_1","status":"completed",
                     "encrypted_content":"ciphertext",
                     "summary":[{"type":"summary_text","text":"inspect"}]},
                    {"type":"function_call","call_id":"call_1","name":"weather",
                     "arguments":"{\\\"city\\\":\\\"Chicago\\\"}"},
                    {"type":"function_call_output","call_id":"call_1","output":"sunny"}
                  ],
                  "instructions":"Answer briefly.",
                  "store":false,
                  "max_output_tokens":128,
                  "reasoning":{"effort":"high","summary":"auto"},
                  "tools":[{"type":"function","name":"weather","description":"Lookup",
                    "parameters":{"type":"object","properties":{"city":{"type":"string"}}},
                    "strict":true}],
                  "tool_choice":{"type":"function","name":"weather"},
                  "parallel_tool_calls":true,
                  "include":["reasoning.encrypted_content"],
                  "text":{"format":{"type":"text"},"verbosity":"low"}
                }
                """, ApiResponseRequest.class);
        ApiResponseRequestValidator validator = new ApiResponseRequestValidatorImpl(
                cacheService(model(List.of(AiModelCapabilityCode.RESPONSES))),
                new ApiKeyProperties(),
                objectMapper,
                registry(new ApiKeyProperties()));

        ValidatedApiResponseRequest validated = validator.validate(principal(), request);
        JsonNode payload = new ApiResponsePayloadFactoryImpl(objectMapper).create(validated);

        assertThat(validated.stream()).isFalse();
        assertThat(validated.effectiveMaxOutputTokens()).isEqualTo(128L);
        assertThat(validated.estimatedInputTokens()).isPositive();
        assertThat(payload.path("model").textValue()).isEqualTo("gpt-test");
        assertThat(payload.path("store").booleanValue()).isFalse();
        assertThat(payload.path("stream").booleanValue()).isFalse();
        assertThat(payload.at("/input/2/encrypted_content").textValue())
                .isEqualTo("ciphertext");
        assertThat(payload.at("/tools/0/type").textValue()).isEqualTo("function");
    }

    @Test
    void treatsExplicitNullAndEmptyOptionalCollectionsAsOmitted() throws Exception {
        ApiResponseRequest omitted = objectMapper.readValue("""
                {"model":"gpt-test","input":"hello"}
                """, ApiResponseRequest.class);
        ApiResponseRequest explicitNulls = objectMapper.readValue("""
                {
                  "model":"gpt-test",
                  "input":"hello",
                  "stream":null,
                  "store":null,
                  "max_output_tokens":null,
                  "reasoning":{"effort":null,"summary":null},
                  "tools":[],
                  "tool_choice":"auto",
                  "parallel_tool_calls":true,
                  "include":[],
                  "prompt_cache_key":null,
                  "service_tier":null,
                  "temperature":null,
                  "top_p":null
                }
                """, ApiResponseRequest.class);
        ApiResponseRequestValidator validator = validator();

        ValidatedApiResponseRequest omittedValidated =
                validator.validate(principal(), omitted);
        ValidatedApiResponseRequest nullValidated =
                validator.validate(principal(), explicitNulls);
        JsonNode payload = new ApiResponsePayloadFactoryImpl(objectMapper)
                .create(nullValidated);

        assertThat(nullValidated.stream()).isFalse();
        assertThat(nullValidated.effectiveMaxOutputTokens())
                .isEqualTo(omittedValidated.effectiveMaxOutputTokens())
                .isEqualTo(512L);
        assertThat(payload.path("store").booleanValue()).isFalse();
        assertThat(payload.path("stream").booleanValue()).isFalse();
        assertThat(payload.path("max_output_tokens").longValue()).isEqualTo(512L);
        assertThat(payload.has("tools")).isFalse();
        assertThat(payload.has("include")).isFalse();
        assertThat(payload.has("tool_choice")).isFalse();
        assertThat(payload.has("parallel_tool_calls")).isFalse();
        assertThat(payload.has("reasoning")).isFalse();
        assertThat(containsNull(payload)).isFalse();
    }

    @Test
    void validatesMaxOutputTokenBoundaryAndKeepsFixedFailureReasons() throws Exception {
        ApiResponseRequest minimum = requestWithMaxOutputTokens("16");
        ApiResponseRequest aboveModelLimit = requestWithMaxOutputTokens("4096");

        ValidatedApiResponseRequest minimumValidated =
                validator().validate(principal(), minimum);
        ValidatedApiResponseRequest capped =
                validator().validate(principal(), aboveModelLimit);

        assertThat(minimumValidated.effectiveMaxOutputTokens()).isEqualTo(16L);
        assertThat(capped.effectiveMaxOutputTokens()).isEqualTo(512L);
        assertValidationFailure("15", ApiChatException.ValidationReason.BELOW_MINIMUM);
        assertValidationFailure("\"16\"", ApiChatException.ValidationReason.WRONG_JSON_TYPE);
        assertValidationFailure("16.5", ApiChatException.ValidationReason.WRONG_JSON_TYPE);
        assertValidationFailure("true", ApiChatException.ValidationReason.WRONG_JSON_TYPE);
    }

    @Test
    void acceptsNullableToolMetadataButKeepsRequiredToolRelationsStrict() throws Exception {
        ApiResponseRequest nullableToolMetadata = objectMapper.readValue("""
                {
                  "model":"gpt-test",
                  "input":[
                    {"type":"reasoning","id":null,"status":null,
                     "encrypted_content":"ciphertext","summary":null}
                  ],
                  "tools":[{"type":"function","name":"weather",
                    "description":null,"parameters":{"type":"object"},"strict":null}],
                  "include":null
                }
                """, ApiResponseRequest.class);
        ApiResponseRequest toolChoiceNone = objectMapper.readValue("""
                {"model":"gpt-test","input":"hello","tools":[],
                 "tool_choice":"none","parallel_tool_calls":false}
                """, ApiResponseRequest.class);
        ApiResponseRequest toolChoiceRequired = objectMapper.readValue("""
                {"model":"gpt-test","input":"hello","tools":[],
                 "tool_choice":"required"}
                """, ApiResponseRequest.class);
        ApiResponseRequest undeclaredFunction = objectMapper.readValue("""
                {"model":"gpt-test","input":"hello",
                 "tool_choice":{"type":"function","name":"weather"}}
                """, ApiResponseRequest.class);

        ValidatedApiResponseRequest validated =
                validator().validate(principal(), nullableToolMetadata);
        ValidatedApiResponseRequest withoutTools =
                validator().validate(principal(), toolChoiceNone);
        JsonNode toolPayload = new ApiResponsePayloadFactoryImpl(objectMapper)
                .create(validated);
        JsonNode withoutToolsPayload = new ApiResponsePayloadFactoryImpl(objectMapper)
                .create(withoutTools);

        assertThat(toolPayload.at("/tools/0/description").isMissingNode()).isTrue();
        assertThat(toolPayload.at("/tools/0/strict").isMissingNode()).isTrue();
        assertThat(toolPayload.at("/input/0/id").isMissingNode()).isTrue();
        assertThat(toolPayload.at("/input/0/status").isMissingNode()).isTrue();
        assertThat(toolPayload.at("/input/0/summary").isMissingNode()).isTrue();
        assertThat(withoutToolsPayload.has("tools")).isFalse();
        assertThat(withoutToolsPayload.has("tool_choice")).isFalse();
        assertThat(withoutToolsPayload.has("parallel_tool_calls")).isFalse();
        assertThatThrownBy(() -> validator().validate(principal(), toolChoiceRequired))
                .isInstanceOf(ApiChatException.class)
                .extracting(failure -> ((ApiChatException) failure).parameter())
                .isEqualTo("tool_choice");
        assertThatThrownBy(() -> validator().validate(principal(), undeclaredFunction))
                .isInstanceOf(ApiChatException.class)
                .extracting(failure -> ((ApiChatException) failure).parameter())
                .isEqualTo("tool_choice");
    }

    @Test
    void rejectsStatefulUnknownAndWrongJsonTypesBeforeReservation() throws Exception {
        ApiResponseRequestValidator validator = new ApiResponseRequestValidatorImpl(
                cacheService(model(List.of(AiModelCapabilityCode.RESPONSES))),
                new ApiKeyProperties(),
                objectMapper,
                registry(new ApiKeyProperties()));
        ApiResponseRequest store = objectMapper.readValue("""
                {"model":"gpt-test","input":"hello","store":true}
                """, ApiResponseRequest.class);
        ApiResponseRequest numericStream = objectMapper.readValue("""
                {"model":"gpt-test","input":"hello","stream":"true"}
                """, ApiResponseRequest.class);

        assertThatThrownBy(() -> validator.validate(principal(), store))
                .isInstanceOf(ApiChatException.class)
                .extracting(failure -> ((ApiChatException) failure).code())
                .isEqualTo(ApiChatErrorCode.INVALID_REQUEST);
        assertThatThrownBy(() -> validator.validate(principal(), numericStream))
                .isInstanceOf(ApiChatException.class);
        assertThatThrownBy(() -> objectMapper.readValue("""
                {"model":"gpt-test","input":"hello","previous_response_id":"resp_1"}
                """, ApiResponseRequest.class))
                .hasRootCauseInstanceOf(ApiChatException.class);
    }

    @Test
    void requiresResponsesCapability() throws Exception {
        ApiResponseRequest request = objectMapper.readValue(
                "{\"model\":\"gpt-test\",\"input\":\"hello\"}",
                ApiResponseRequest.class);
        ApiResponseRequestValidator validator = new ApiResponseRequestValidatorImpl(
                cacheService(model(List.of(AiModelCapabilityCode.CHAT_COMPLETIONS))),
                new ApiKeyProperties(),
                objectMapper,
                registry(new ApiKeyProperties()));

        assertThatThrownBy(() -> validator.validate(principal(), request))
                .isInstanceOf(ApiChatException.class)
                .extracting(failure -> ((ApiChatException) failure).code())
                .isEqualTo(ApiChatErrorCode.MODEL_NOT_FOUND);
    }

    @Test
    void returnsForbiddenOnlyAfterResponsesCapabilityHasBeenConfirmed() throws Exception {
        ApiResponseRequest request = objectMapper.readValue(
                "{\"model\":\"gpt-test\",\"input\":\"hello\"}",
                ApiResponseRequest.class);
        ApiResponseRequestValidator validator = new ApiResponseRequestValidatorImpl(
                cacheService(model(List.of(AiModelCapabilityCode.RESPONSES))),
                new ApiKeyProperties(),
                objectMapper,
                registry(new ApiKeyProperties()));
        ApiKeyPrincipal unauthorized = new ApiKeyPrincipal(
                new byte[16], 17L, new byte[32], "B".repeat(43), Set.of());

        assertThatThrownBy(() -> validator.validate(unauthorized, request))
                .isInstanceOf(ApiChatException.class)
                .extracting(failure -> ((ApiChatException) failure).code())
                .isEqualTo(ApiChatErrorCode.MODEL_NOT_ALLOWED);
    }

    @Test
    void rejectsUnknownAndDuplicatedFunctionCallOutputs() throws Exception {
        ApiResponseRequestValidator validator = new ApiResponseRequestValidatorImpl(
                cacheService(model(List.of(AiModelCapabilityCode.RESPONSES))),
                new ApiKeyProperties(),
                objectMapper,
                registry(new ApiKeyProperties()));
        ApiResponseRequest unknown = objectMapper.readValue("""
                {"model":"gpt-test","input":[
                  {"type":"function_call_output","call_id":"call_missing","output":"x"}
                ]}
                """, ApiResponseRequest.class);
        ApiResponseRequest duplicated = objectMapper.readValue("""
                {"model":"gpt-test","input":[
                  {"type":"function_call","call_id":"call_1","name":"f","arguments":"{}"},
                  {"type":"function_call_output","call_id":"call_1","output":"x"},
                  {"type":"function_call_output","call_id":"call_1","output":"y"}
                ]}
                """, ApiResponseRequest.class);

        assertThatThrownBy(() -> validator.validate(principal(), unknown))
                .isInstanceOf(ApiChatException.class);
        assertThatThrownBy(() -> validator.validate(principal(), duplicated))
                .isInstanceOf(ApiChatException.class);
    }

    @Test
    void providerRegistryRejectsDuplicateAndUnknownProviderTypes() {
        ApiResponseProviderAdapter first = adapter(AiModelProvider.OPENAI);
        ApiResponseProviderAdapter duplicate = adapter(AiModelProvider.OPENAI);

        assertThatThrownBy(() -> new ApiResponseProviderAdapterRegistry(
                java.util.Map.of("first", first, "duplicate", duplicate)))
                .isInstanceOf(IllegalStateException.class);
        ApiResponseProviderAdapterRegistry registry =
                new ApiResponseProviderAdapterRegistry(
                        java.util.Map.of("openai", first));
        assertThatThrownBy(() -> registry.getRequired("unsupported"))
                .isInstanceOf(ApiChatException.class)
                .extracting(failure -> ((ApiChatException) failure).code())
                .isEqualTo(ApiChatErrorCode.MODEL_NOT_FOUND);
    }

    @Test
    void parsesNativeFunctionDeltasAndAuthoritativeUsageWithoutDone() throws Exception {
        ApiResponseProtocolParserImpl parser = new ApiResponseProtocolParserImpl(objectMapper);
        var delta = parser.parseSse(new ApiInferenceSseEvent(
                "response.function_call_arguments.delta",
                """
                {"type":"response.function_call_arguments.delta","sequence_number":3,
                 "item_id":"fc_1","output_index":0,"delta":"{\\\"city\\\":"}
                """));
        var completed = parser.parseSse(new ApiInferenceSseEvent(
                "message",
                """
                {"type":"response.completed","sequence_number":4,"response":{
                 "object":"response","status":"completed",
                 "output":[{"type":"function_call","call_id":"call_1"}],
                 "usage":{"input_tokens":20,"output_tokens":5,
                 "input_tokens_details":{"cached_tokens":7}}}}
                """));
        var json = parser.parseJson(objectMapper.readTree("""
                {"object":"response","status":"incomplete","output":[],
                 "incomplete_details":{"reason":"max_output_tokens"},
                 "usage":{"input_tokens":20,"output_tokens":5,
                 "input_tokens_details":{"cached_tokens":7}}}
                """));

        assertThat(delta.eventName()).isEqualTo("response.function_call_arguments.delta");
        assertThat(delta.outputUtf8Bytes()).isPositive();
        assertThat(completed.terminalKind()).isEqualTo(TerminalKind.COMPLETED);
        assertThat(completed.finishReason()).isEqualTo("TOOL_CALLS");
        assertThat(completed.usage()).isEqualTo(new ApiInferenceUsage(20, 5, 7));
        assertThat(json.status()).isEqualTo(Status.INCOMPLETE);
        assertThat(json.finishReason()).isEqualTo("MAX_OUTPUT_TOKENS");
    }

    @Test
    void decodesUtf8AcrossChunksCrLfMultilineDataAndCommentHeartbeat() {
        ApiInferenceSseDecoder decoder = new ApiInferenceSseDecoder(1024, 2048);
        byte[] bytes = """
                : heartbeat\r
                event: response.output_text.delta\r
                data: {"type":"response.output_text.delta",\r
                data: "delta":"芝"}\r
                \r
                """.getBytes(StandardCharsets.UTF_8);
        int split = bytes.length - 4;

        var first = decoder.accept(bytes, 0, split);
        var second = decoder.accept(bytes, split, bytes.length - split);
        var events = new java.util.ArrayList<ApiInferenceSseEvent>();
        events.addAll(first);
        events.addAll(second);
        events.addAll(decoder.finish());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).eventName()).isEqualTo("response.output_text.delta");
        assertThat(events.get(0).data()).contains("\n", "芝");
    }

    private static ApiKeyPrincipal principal() {
        return new ApiKeyPrincipal(
                new byte[16], 17L, new byte[32], "B".repeat(43), Set.of(7L));
    }

    private ApiResponseRequestValidator validator() {
        return new ApiResponseRequestValidatorImpl(
                cacheService(model(List.of(AiModelCapabilityCode.RESPONSES))),
                new ApiKeyProperties(),
                objectMapper,
                registry(new ApiKeyProperties()));
    }

    private LooseOpenAiRequestNormalizerRegistry registry(ApiKeyProperties properties) {
        return new LooseOpenAiRequestNormalizerRegistry(Map.of(
                "responsesLooseOpenAiRequestNormalizer",
                new ResponsesLooseOpenAiRequestNormalizerImpl(properties, objectMapper)));
    }

    private ApiResponseRequest requestWithMaxOutputTokens(String jsonValue)
            throws Exception {
        return objectMapper.readValue(
                "{\"model\":\"gpt-test\",\"input\":\"hello\","
                        + "\"max_output_tokens\":" + jsonValue + "}",
                ApiResponseRequest.class);
    }

    private void assertValidationFailure(
            String jsonValue,
            ApiChatException.ValidationReason expectedReason) throws Exception {
        ApiResponseRequest request = requestWithMaxOutputTokens(jsonValue);
        assertThatThrownBy(() -> validator().validate(principal(), request))
                .isInstanceOf(ApiChatException.class)
                .satisfies(failure -> {
                    ApiChatException exception = (ApiChatException) failure;
                    assertThat(exception.parameter()).isEqualTo("max_output_tokens");
                    assertThat(exception.validationReason()).isEqualTo(expectedReason);
                });
    }

    private static boolean containsNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return true;
        }
        if (!node.isContainerNode()) {
            return false;
        }
        for (JsonNode child : node) {
            if (containsNull(child)) {
                return true;
            }
        }
        return false;
    }

    private static AiModelCacheEntry model(List<AiModelCapabilityCode> capabilities) {
        return model(capabilities, "openai");
    }

    private static AiModelCacheEntry model(
            List<AiModelCapabilityCode> capabilities,
            String vendor) {
        return new AiModelCacheEntry(
                7L,
                "gpt-test",
                vendor,
                "test",
                null,
                List.of(),
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                4_096,
                512,
                capabilities);
    }

    private static AiModelCacheService cacheService(AiModelCacheEntry model) {
        AiModelCacheSnapshot snapshot = new AiModelCacheSnapshot(
                AiModelCacheSnapshot.CURRENT_SCHEMA_VERSION, List.of(model));
        return new AiModelCacheService() {
            @Override
            public Optional<AiModelCacheSnapshot> findEnabledSnapshot() {
                return Optional.of(snapshot);
            }

            @Override
            public AiModelCacheSnapshot getOrLoadEnabledSnapshot() {
                return snapshot;
            }

            @Override
            public void refreshEnabledSnapshot() {
                throw new UnsupportedOperationException("read-only fixture");
            }
        };
    }

    private static ApiResponseProviderAdapter adapter(AiModelProvider provider) {
        return new ApiResponseProviderAdapter() {
            @Override
            public AiModelProvider type() {
                return provider;
            }

            @Override
            public ObjectNode adapt(ValidatedApiResponseRequest request) {
                throw new UnsupportedOperationException("Registry-only fixture");
            }
        };
    }
}
