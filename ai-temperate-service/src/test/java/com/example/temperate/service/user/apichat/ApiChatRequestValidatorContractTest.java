package com.example.temperate.service.user.apichat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheService;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheSnapshot;
import com.example.temperate.service.user.apichat.impl.ApiChatRequestValidatorImpl;
import com.example.temperate.service.user.apichat.provider.impl.ApiChatPayloadFactoryImpl;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.example.temperate.service.user.openaicompatibility.LooseOpenAiRequestNormalizerRegistry;
import com.example.temperate.service.user.openaicompatibility.OpenAiRequestPayloadMode;
import com.example.temperate.service.user.openaicompatibility.impl.ChatLooseOpenAiRequestNormalizerImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 该测试是来约束旧 Chat 严格回滚路径与所有厂商共享的宽松 JSON 路径、Token 上限、授权和上下文边界。
 */
final class ApiChatRequestValidatorContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ApiKeyProperties defaultProperties = new ApiKeyProperties();
    private final ApiChatRequestValidator validator = new ApiChatRequestValidatorImpl(
            cacheService(model()),
            defaultProperties,
            objectMapper,
            registry(defaultProperties));
    private final ApiKeyPrincipal principal = new ApiKeyPrincipal(
            1L, 2L, new byte[32], "A".repeat(43), Set.of(7L));

    @Test
    void routesEnabledModelsThroughTheLooseCompatibilityValidator() throws Exception {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.getOpenAiCompatibility().setEnabled(true);
        ObjectNode raw = (ObjectNode) objectMapper.readTree("""
                {"model":"gpt-test","messages":[{"role":"developer",
                 "content":"policy"},{"role":"user","content":"hello"}],
                 "stream":false,"verbosity":"low","logprobs":true}
                """);

        ValidatedApiChatRequest validated = validator(properties, model())
                .validate(principal, raw);

        assertThat(validated.payloadMode())
                .isEqualTo(OpenAiRequestPayloadMode.LOOSE_NORMALIZED);
        assertThat(validated.stream()).isFalse();
        assertThat(validated.normalizedPayload().path("verbosity").textValue())
                .isEqualTo("low");
        ObjectNode payload = new ApiChatPayloadFactoryImpl(objectMapper)
                .create(validated);
        assertThat(payload.path("verbosity").textValue()).isEqualTo("low");
        assertThat(payload.path("logprobs").booleanValue()).isTrue();
        assertThat(payload.at("/messages/0/role").textValue())
                .isEqualTo("developer");
    }

    @Test
    void routesNonOpenAiModelsThroughTheSameLooseContract() throws Exception {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.getOpenAiCompatibility().setEnabled(true);
        ObjectNode raw = (ObjectNode) objectMapper.readTree("""
                {"model":"gpt-test","messages":[{"role":"user",
                 "content":"hello"}],"stream":false}
                """);

        ValidatedApiChatRequest validated = validator(
                properties, model(4_096, "xai")).validate(principal, raw);

        assertThat(validated.payloadMode())
                .isEqualTo(OpenAiRequestPayloadMode.LOOSE_NORMALIZED);
        assertThat(validated.stream()).isFalse();
    }

    @Test
    void disabledCompatibilitySwitchRestoresLegacyStrictDtoBehavior() throws Exception {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.getOpenAiCompatibility().setEnabled(false);
        ObjectNode raw = (ObjectNode) objectMapper.readTree("""
                {"model":"gpt-test","messages":[{"role":"user","content":"hello",
                 "agent":"workbuddy"}],"stream":false}
                """);

        assertThatThrownBy(() -> validator(properties, model()).validate(principal, raw))
                .isInstanceOf(ApiChatException.class);
    }

    @Test
    void appliesClientAndModelTokenMinimumToValidationAndBillingInput() throws Exception {
        ApiChatRequest request = request("""
                {"model":"GPT-TEST","messages":[{"role":"user","content":"hello"}],
                 "stream":true,"max_completion_tokens":200,"temperature":0.5,
                 "seed":3,"n":1}
                """);

        ValidatedApiChatRequest validated = validator.validate(principal, request);

        assertThat(validated.effectiveMaxOutputTokens()).isEqualTo(200);
        assertThat(validated.estimatedPromptTokens()).isPositive();
    }

    @Test
    void rejectsNumericStringsAndCompetingTokenLimits() throws Exception {
        ApiChatRequest numericString = request("""
                {"model":"gpt-test","messages":[{"role":"user","content":"hello"}],
                 "stream":true,"max_tokens":"200"}
                """);
        ApiChatRequest competing = request("""
                {"model":"gpt-test","messages":[{"role":"user","content":"hello"}],
                 "stream":true,"max_tokens":200,"max_completion_tokens":200}
                """);

        assertInvalid(numericString, "max_tokens");
        assertInvalid(competing, "max_completion_tokens");
    }

    @Test
    void rejectsFalseStreamAndEmptyContentParts() throws Exception {
        ApiChatRequest falseStream = request("""
                {"model":"gpt-test","messages":[{"role":"user","content":"hello"}],
                 "stream":false}
                """);
        ApiChatRequest emptyContentParts = request("""
                {"model":"gpt-test","messages":[{"role":"user","content":[]}],
                 "stream":true}
                """);

        assertThatThrownBy(() -> validator.validate(principal, falseStream))
                .isInstanceOf(ApiChatException.class)
                .extracting(failure -> ((ApiChatException) failure).code())
                .isEqualTo(ApiChatErrorCode.STREAM_REQUIRED);
        assertInvalid(emptyContentParts, "messages[0].content");
    }

    @Test
    void rejectsActiveApiKeyWithoutRequestedModelGrant() throws Exception {
        ApiKeyPrincipal noGrant = new ApiKeyPrincipal(
                1L, 2L, new byte[32], "A".repeat(43), Set.of());

        assertThatThrownBy(() -> validator.validate(noGrant, request("""
                {"model":"gpt-test","messages":[{"role":"user","content":"hello"}],
                 "stream":true}
                """)))
                .isInstanceOf(ApiChatException.class)
                .satisfies(failure -> {
                    ApiChatException exception = (ApiChatException) failure;
                    assertThat(exception.code()).isEqualTo(ApiChatErrorCode.MODEL_NOT_ALLOWED);
                    assertThat(exception.code().status()).isEqualTo(403);
                    assertThat(exception.parameter()).isEqualTo("model");
                });
    }

    @Test
    void acceptsTextOnlyContentPartsForAgentMessagesAndCountsFlattenedText()
            throws Exception {
        ApiChatRequest contentParts = request("""
                {
                  "model":"gpt-test",
                  "messages":[
                    {"role":"system","content":[
                      {"type":"text","text":"system "},
                      {"type":"text","text":"rules"}]},
                    {"role":"user","content":[
                      {"type":"text","text":"hello "},
                      {"type":"text","text":"world"}]},
                    {"role":"assistant","content":[
                      {"type":"text","text":"checking "},
                      {"type":"text","text":"weather"}],
                     "reasoning_content":"use tool",
                     "tool_calls":[{"id":"call_weather","type":"function",
                     "function":{"name":"weather","arguments":"{}"}}]},
                    {"role":"tool","tool_call_id":"call_weather","content":"sunny"}
                  ],
                  "stream":true
                }
                """);
        ApiChatRequest flattened = request("""
                {
                  "model":"gpt-test",
                  "messages":[
                    {"role":"system","content":"system rules"},
                    {"role":"user","content":"hello world"},
                    {"role":"assistant","content":"checking weather",
                     "reasoning_content":"use tool",
                     "tool_calls":[{"id":"call_weather","type":"function",
                     "function":{"name":"weather","arguments":"{}"}}]},
                    {"role":"tool","tool_call_id":"call_weather","content":"sunny"}
                  ],
                  "stream":true
                }
                """);

        ValidatedApiChatRequest contentPartsValidated =
                validator.validate(principal, contentParts);
        ValidatedApiChatRequest flattenedValidated =
                validator.validate(principal, flattened);

        assertThat(contentPartsValidated.estimatedPromptTokens())
                .isEqualTo(flattenedValidated.estimatedPromptTokens());
    }

    @Test
    void rejectsMalformedOrNonTextContentPartsWithPreciseParameters()
            throws Exception {
        assertInvalid(request("""
                {"model":"gpt-test","messages":[{"role":"user","content":["hello"]}],
                 "stream":true}
                """), "messages[0].content[0]");
        assertInvalid(request("""
                {"model":"gpt-test","messages":[{"role":"user","content":[
                 {"text":"hello"}]}],"stream":true}
                """), "messages[0].content[0].type");
        assertInvalid(request("""
                {"model":"gpt-test","messages":[{"role":"user","content":[
                 {"type":"text"}]}],"stream":true}
                """), "messages[0].content[0].text");
        assertInvalid(request("""
                {"model":"gpt-test","messages":[{"role":"user","content":[
                 {"type":"text","text":42}]}],"stream":true}
                """), "messages[0].content[0].text");
        for (String unsupportedType : List.of(
                "image", "image_url", "input_image", "audio", "file")) {
            assertInvalid(request("""
                    {"model":"gpt-test","messages":[{"role":"user","content":[
                     {"type":"%s","text":"unsupported"}]}],"stream":true}
                    """.formatted(unsupportedType)), "messages[0].content[0].type");
        }
        assertInvalid(request("""
                {"model":"gpt-test","messages":[{"role":"user","content":[
                 {"type":"text","text":"hello","extra":true}]}],"stream":true}
                """), "messages[0].content[0].extra");
        assertInvalid(request("""
                {"model":"gpt-test","messages":[{"role":"tool",
                 "tool_call_id":"call_1","content":[{"type":"text","text":"result"}]}],
                 "stream":true}
                """), "messages[0].content");
    }

    @Test
    void rejectsOversizedCombinedTextContentParts() throws Exception {
        ApiChatRequest request = request("""
                {"model":"gpt-test","messages":[{"role":"user","content":[
                 {"type":"text","text":"%s"},{"type":"text","text":"x"}]}],
                 "stream":true}
                """.formatted("a".repeat(262_144)));

        assertInvalid(request, "messages[0].content");
    }

    @Test
    void treatsMissingStreamAsStreamRequiredAndAllowsEmptyStreamOptions() throws Exception {
        ApiChatRequest missingStream = request("""
                {"model":"gpt-test","messages":[{"role":"user","content":"hello"}]}
                """);
        ApiChatRequest emptyOptions = request("""
                {"model":"gpt-test","messages":[{"role":"user","content":"hello"}],
                 "stream":true,"stream_options":{}}
                """);

        assertThatThrownBy(() -> validator.validate(principal, missingStream))
                .isInstanceOf(ApiChatException.class)
                .extracting(failure -> ((ApiChatException) failure).code())
                .isEqualTo(ApiChatErrorCode.STREAM_REQUIRED);
        assertThat(validator.validate(principal, emptyOptions).includeUsage()).isFalse();
    }

    @Test
    void acceptsCcSwitchClaudeCompatibilityFieldsAndAccountsForReasoningHistory()
            throws Exception {
        ApiChatRequest withoutReasoning = request("""
                {"model":"gpt-test","messages":[{"role":"user","content":"hello"}],
                 "stream":true}
                """);
        ApiChatRequest request = request("""
                {
                  "model":"gpt-test",
                  "messages":[
                    {"role":"user","content":"What is the weather?"},
                    {"role":"assistant","content":null,"reasoning_content":"Need a weather tool.",
                     "tool_calls":[{"id":"call_weather","type":"function",
                     "function":{"name":"weather","arguments":"{\\"city\\":\\"Chicago\\"}"}}]},
                    {"role":"tool","tool_call_id":"call_weather","content":"sunny"}
                  ],
                  "stream":true,
                  "stream_options":{"include_usage":true},
                  "reasoning_effort":"high",
                  "prompt_cache_key":"cc-switch-session-1",
                  "store":false,
                  "service_tier":"auto"
                }
                """);

        ValidatedApiChatRequest validated = validator.validate(principal, request);

        assertThat(validated.includeUsage()).isTrue();
        assertThat(validated.estimatedPromptTokens())
                .isGreaterThan(validator.validate(principal, withoutReasoning)
                        .estimatedPromptTokens());
    }

    @Test
    void acceptsClaudeCodeToolSetWithASevenThousandCharacterDescription()
            throws Exception {
        ArrayList<String> descriptions = new ArrayList<>();
        descriptions.add("x".repeat(7_125));
        for (int index = 1; index < 25; index++) {
            descriptions.add("Safe description for Claude Code tool " + index);
        }
        ApiChatRequest longDescriptions = requestWithToolDescriptions(descriptions);
        ApiChatRequest shortDescriptions = requestWithToolDescriptions(
                Collections.nCopies(25, "short"));
        ApiChatRequestValidator largeContextValidator = validator(
                new ApiKeyProperties(), model(1_000_000));

        ValidatedApiChatRequest validated = largeContextValidator.validate(
                principal, longDescriptions);
        ValidatedApiChatRequest shortValidated = largeContextValidator.validate(
                principal, shortDescriptions);
        long longToolBytes = objectMapper.writeValueAsBytes(
                longDescriptions.tools()).length;
        long shortToolBytes = objectMapper.writeValueAsBytes(
                shortDescriptions.tools()).length;
        long expectedTokenDifference = Math.ceilDiv(5L + longToolBytes, 3L)
                - Math.ceilDiv(5L + shortToolBytes, 3L);

        assertThat(validated.estimatedPromptTokens()
                - shortValidated.estimatedPromptTokens())
                .isEqualTo(expectedTokenDifference);
    }

    @Test
    void enforcesPerToolDescriptionBudgetByUtf8BytesWithPreciseParameter()
            throws Exception {
        ApiChatRequestValidator largeContextValidator = validator(
                new ApiKeyProperties(), model(1_000_000));
        ApiChatRequest exactAscii = requestWithToolDescriptions(
                List.of("a".repeat(32_768)));
        ApiChatRequest overAscii = requestWithToolDescriptions(
                List.of("a".repeat(32_769)));
        ApiChatRequest overMultibyte = requestWithToolDescriptions(
                List.of("你".repeat(10_923)));

        assertThat("你".repeat(10_923).getBytes(StandardCharsets.UTF_8))
                .hasSize(32_769);
        assertThat(largeContextValidator.validate(principal, exactAscii)).isNotNull();
        assertDescriptionTooLarge(largeContextValidator, overAscii, 0);
        assertDescriptionTooLarge(largeContextValidator, overMultibyte, 0);
    }

    @Test
    void enforcesAggregateSerializedToolBudgetWithoutChangingToolCountLimit()
            throws Exception {
        ApiChatRequestValidator largeContextValidator = validator(
                new ApiKeyProperties(), model(1_000_000));
        ApiChatRequest belowAggregateLimit = requestWithToolDescriptions(
                Collections.nCopies(15, "a".repeat(32_768)));
        ApiChatRequest aboveAggregateLimit = requestWithToolDescriptions(
                Collections.nCopies(16, "a".repeat(32_768)));
        ApiChatRequest maximumCount = requestWithToolDescriptions(
                Collections.nCopies(128, "short"));

        assertThat(objectMapper.writeValueAsBytes(belowAggregateLimit.tools()).length)
                .isLessThanOrEqualTo(524_288);
        assertThat(objectMapper.writeValueAsBytes(aboveAggregateLimit.tools()).length)
                .isGreaterThan(524_288);
        assertThat(largeContextValidator.validate(principal, belowAggregateLimit))
                .isNotNull();
        assertThat(largeContextValidator.validate(principal, maximumCount)).isNotNull();
        assertThatThrownBy(() -> largeContextValidator.validate(
                principal, aboveAggregateLimit))
                .isInstanceOf(ApiChatException.class)
                .satisfies(failure -> {
                    ApiChatException exception = (ApiChatException) failure;
                    assertThat(exception.code()).isEqualTo(ApiChatErrorCode.INVALID_REQUEST);
                    assertThat(exception.getMessage())
                            .isEqualTo("Tool definitions exceed the allowed UTF-8 size.");
                    assertThat(exception.parameter()).isEqualTo("tools");
                });
    }

    @Test
    void acceptsMissingEmptyAndNormalToolDescriptions() throws Exception {
        ApiChatRequest request = request("""
                {
                  "model":"gpt-test",
                  "messages":[{"role":"user","content":"hello"}],
                  "stream":true,
                  "tools":[
                    {"type":"function","function":{"name":"missing_description",
                     "parameters":{"type":"object"}}},
                    {"type":"function","function":{"name":"null_description",
                     "description":null,"parameters":{"type":"object"}}},
                    {"type":"function","function":{"name":"empty_description",
                     "description":"","parameters":{"type":"object"}}},
                    {"type":"function","function":{"name":"normal_description",
                     "description":"normal","parameters":{"type":"object"}}}
                  ]
                }
                """);

        assertThat(validator.validate(principal, request)).isNotNull();
    }

    @Test
    void toolBudgetDiagnosticsExposeOnlyBoundedByteCounts() throws Exception {
        ApiChatRequestValidator largeContextValidator = validator(
                new ApiKeyProperties(), model(1_000_000));
        String canary = "description-secret-canary-" + "x".repeat(32_768);
        ApiChatRequest invalid = requestWithToolDescriptions(List.of(canary));

        try (LogCapture logs = LogCapture.start()) {
            assertDescriptionTooLarge(largeContextValidator, invalid, 0);

            assertThat(logs.joined())
                    .contains("rule=FUNCTION_DESCRIPTION_TOO_LARGE")
                    .contains("parameter=tools[0].function.description")
                    .contains("descriptionBytes=")
                    .doesNotContain(canary, "description-secret-canary");
        }
    }

    @Test
    void aggregateToolBudgetDiagnosticsExposeOnlyCollectionMetrics()
            throws Exception {
        ApiChatRequestValidator largeContextValidator = validator(
                new ApiKeyProperties(), model(1_000_000));
        String canary = "aggregate-description-secret-" + "x".repeat(32_739);
        ApiChatRequest invalid = requestWithToolDescriptions(
                Collections.nCopies(16, canary));

        try (LogCapture logs = LogCapture.start()) {
            assertThatThrownBy(() -> largeContextValidator.validate(principal, invalid))
                    .isInstanceOf(ApiChatException.class);

            assertThat(logs.joined())
                    .contains("rule=TOOL_DEFINITIONS_TOO_LARGE")
                    .contains("parameter=tools")
                    .contains("collectionSize=16")
                    .contains("toolDefinitionsBytes=")
                    .doesNotContain(canary, "aggregate-description-secret");
        }
    }

    @Test
    void rejectsUnsupportedCompatibilityFieldsAndInvalidCompatibilityValues()
            throws Exception {
        ApiChatRequest invalidStore = request("""
                {"model":"gpt-test","messages":[{"role":"user","content":"hello"}],
                 "stream":true,"store":true}
                """);
        ApiChatRequest invalidEffort = request("""
                {"model":"gpt-test","messages":[{"role":"user","content":"hello"}],
                 "stream":true,"reasoning_effort":"adaptive"}
                """);
        ApiChatRequest invalidReasoningRole = request("""
                {"model":"gpt-test","messages":[{"role":"user","content":"hello",
                 "reasoning_content":"not allowed"}],"stream":true}
                """);
        ApiChatRequest overlongCacheKey = request("""
                {"model":"gpt-test","messages":[{"role":"user","content":"hello"}],
                 "stream":true,"prompt_cache_key":"%s"}
                """.formatted("p".repeat(257)));

        assertInvalid(invalidStore, "store");
        assertInvalid(invalidEffort, "reasoning_effort");
        assertInvalid(invalidReasoningRole, "messages[0].reasoning_content");
        assertInvalid(overlongCacheKey, "prompt_cache_key");
        assertThatThrownBy(() -> request("""
                {"model":"gpt-test","messages":[{"role":"user","content":"hello"}],
                 "stream":true,"thinking":true}
                """))
                // Jackson 会将 @JsonAnySetter 的业务异常包装为映射异常；Web 层会继续解开该根因生成 OpenAI 400。
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class)
                .satisfies(failure -> {
                    assertThat(failure.getCause()).isInstanceOf(ApiChatException.class);
                    assertThat(((ApiChatException) failure.getCause()).parameter())
                            .isEqualTo("thinking");
                });
    }

    @Test
    void logsPreciseToolValidationRulesWithoutToolDefinitionContent()
            throws Exception {
        try (LogCapture logs = LogCapture.start()) {
            assertToolRejection(
                    logs,
                    """
                    [{"type":"browser","function":{"name":"valid_name",
                    "description":"type-secret","parameters":{"type":"object"}}}]
                    """,
                    "TOOL_TYPE_NOT_FUNCTION",
                    0,
                    "type-secret");
            assertToolRejection(
                    logs,
                    """
                    [{"type":"function"}]
                    """,
                    "FUNCTION_MISSING",
                    0,
                    "function-missing-secret");
            assertToolRejection(
                    logs,
                    """
                    [{"type":"function","function":{"name":"invalid secret name",
                    "description":"name-secret","parameters":{"type":"object"}}}]
                    """,
                    "FUNCTION_NAME_INVALID",
                    0,
                    "invalid secret name");
            assertToolRejection(
                    logs,
                    """
                    [{"type":"function","function":{"name":"duplicate_secret",
                    "parameters":{"type":"object"}}},
                    {"type":"function","function":{"name":"duplicate_secret",
                    "parameters":{"type":"object"}}}]
                    """,
                    "FUNCTION_NAME_DUPLICATE",
                    1,
                    "duplicate_secret");
            assertToolRejection(
                    logs,
                    """
                    [{"type":"function","function":{"name":"valid_name",
                    "description":"parameters-secret"}}]
                    """,
                    "FUNCTION_PARAMETERS_MISSING",
                    0,
                    "parameters-secret");
            assertToolRejection(
                    logs,
                    """
                    [{"type":"function","function":{"name":"valid_name",
                    "description":"array-secret","parameters":[]}}]
                    """,
                    "FUNCTION_PARAMETERS_NOT_OBJECT",
                    0,
                    "array-secret");
        }
    }

    @Test
    void redactsClientControlledUnsupportedPartFieldFromDiagnosticParameter()
            throws Exception {
        String canaryField = "prompt-secret-canary";
        ApiChatRequest invalid = request("""
                {"model":"gpt-test","messages":[{"role":"user","content":[
                  {"type":"text","text":"hello","prompt-secret-canary":true}]}],
                 "stream":true}
                """);

        try (LogCapture logs = LogCapture.start()) {
            assertThatThrownBy(() -> validator.validate(principal, invalid))
                    .isInstanceOf(ApiChatException.class)
                    .satisfies(failure -> assertThat(
                            ((ApiChatException) failure).parameter())
                            .isEqualTo("messages[0].content[0]." + canaryField));

            assertThat(logs.joined())
                    .contains("rule=MESSAGE_CONTENT_PART_FIELD_UNSUPPORTED")
                    .contains("parameter=messages[].content[].field")
                    .doesNotContain(canaryField);
        }
    }

    @Test
    void logsNullCountAndSchemaDepthRulesWithoutChangingExternalErrors()
            throws Exception {
        try (LogCapture logs = LogCapture.start()) {
            assertToolRejection(
                    logs,
                    "[null]",
                    "TOOL_NULL",
                    0,
                    "null-tool-canary");

            logs.clear();
            String validTool = """
                    {"type":"function","function":{"name":"same_name",
                    "parameters":{"type":"object"}}}
                    """.strip();
            ApiChatRequest tooMany = request("""
                    {"model":"gpt-test","messages":[{"role":"user","content":"hello"}],
                     "stream":true,"tools":[%s]}
                    """.formatted(String.join(",",
                    Collections.nCopies(129, validTool))));
            assertThatThrownBy(() -> validator.validate(principal, tooMany))
                    .isInstanceOf(ApiChatException.class)
                    .satisfies(failure -> {
                        ApiChatException exception = (ApiChatException) failure;
                        assertThat(exception.getMessage())
                                .isEqualTo("Too many tools were provided.");
                        assertThat(exception.parameter()).isEqualTo("tools");
                    });
            assertThat(logs.joined())
                    .contains("rule=TOOL_COUNT_EXCEEDED")
                    .contains("collectionSize=129")
                    .doesNotContain("same_name");

            logs.clear();
            String schema = "{\"type\":\"object\"}";
            for (int depth = 0; depth < 17; depth++) {
                schema = "{\"schema-secret-canary\":" + schema + "}";
            }
            ApiChatRequest tooDeep = request("""
                    {"model":"gpt-test","messages":[{"role":"user","content":"hello"}],
                     "stream":true,"tools":[{"type":"function","function":{
                       "name":"deep_schema","parameters":%s}}]}
                    """.formatted(schema));
            assertThatThrownBy(() -> validator.validate(principal, tooDeep))
                    .isInstanceOf(ApiChatException.class)
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .isEqualTo("Function JSON Schema is too complex."));
            assertThat(logs.joined())
                    .contains("rule=FUNCTION_SCHEMA_TOO_DEEP")
                    .contains("toolIndex=0")
                    .doesNotContain("schema-secret-canary", "deep_schema");
        }
    }

    private void assertToolRejection(
            LogCapture logs,
            String toolsJson,
            String expectedRule,
            int expectedToolIndex,
            String forbiddenText) throws Exception {
        logs.clear();
        ApiChatRequest invalid = request("""
                {"model":"gpt-test","messages":[{"role":"user","content":"hello"}],
                 "stream":true,"tools":%s}
                """.formatted(toolsJson));

        assertThatThrownBy(() -> validator.validate(principal, invalid))
                .isInstanceOf(ApiChatException.class)
                .satisfies(failure -> {
                    ApiChatException exception = (ApiChatException) failure;
                    assertThat(exception.code()).isEqualTo(ApiChatErrorCode.INVALID_REQUEST);
                    assertThat(exception.getMessage()).isEqualTo("Function tool is invalid.");
                    assertThat(exception.parameter()).isEqualTo("tools");
                });

        assertThat(logs.joined())
                .contains("event=api_chat_validation_rejected")
                .contains("diagnosticSchema=chat-diag-v1")
                .contains("rule=" + expectedRule)
                .contains("toolIndex=" + expectedToolIndex)
                .doesNotContain(forbiddenText)
                .doesNotContain(toolsJson);
    }

    private void assertInvalid(ApiChatRequest request, String parameter) {
        assertThatThrownBy(() -> validator.validate(principal, request))
                .isInstanceOf(ApiChatException.class)
                .satisfies(failure -> assertThat(((ApiChatException) failure).parameter())
                        .isEqualTo(parameter));
    }

    private void assertDescriptionTooLarge(
            ApiChatRequestValidator target,
            ApiChatRequest request,
            int toolIndex) {
        assertThatThrownBy(() -> target.validate(principal, request))
                .isInstanceOf(ApiChatException.class)
                .satisfies(failure -> {
                    ApiChatException exception = (ApiChatException) failure;
                    assertThat(exception.code()).isEqualTo(ApiChatErrorCode.INVALID_REQUEST);
                    assertThat(exception.getMessage()).isEqualTo(
                            "Function tool description exceeds the allowed UTF-8 size.");
                    assertThat(exception.parameter()).isEqualTo(
                            "tools[" + toolIndex + "].function.description");
                });
    }

    private ApiChatRequest requestWithToolDescriptions(List<String> descriptions)
            throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", "gpt-test");
        root.put("stream", true);
        root.putObject("stream_options").put("include_usage", true);
        root.putArray("messages")
                .addObject()
                .put("role", "user")
                .put("content", "hello");
        ArrayNode tools = root.putArray("tools");
        for (int index = 0; index < descriptions.size(); index++) {
            ObjectNode function = tools.addObject()
                    .put("type", "function")
                    .putObject("function");
            function.put("name", "tool_" + index);
            function.put("description", descriptions.get(index));
            function.putObject("parameters")
                    .put("type", "object")
                    .putObject("properties")
                    .putObject("value")
                    .put("type", "string");
        }
        return objectMapper.treeToValue(root, ApiChatRequest.class);
    }

    private ApiChatRequest request(String json) throws Exception {
        return objectMapper.readValue(json, ApiChatRequest.class);
    }

    private static AiModelCacheEntry model() {
        return model(4_096);
    }

    private static AiModelCacheEntry model(int contextWindowTokens) {
        return model(contextWindowTokens, "openai");
    }

    private static AiModelCacheEntry model(
            int contextWindowTokens,
            String vendor) {
        return new AiModelCacheEntry(
                7L, "gpt-test", vendor, "test", null, List.of(),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                contextWindowTokens, 512,
                List.of(AiModelCapabilityCode.CHAT_COMPLETIONS));
    }

    private ApiChatRequestValidator validator(
            ApiKeyProperties properties,
            AiModelCacheEntry model) {
        return new ApiChatRequestValidatorImpl(
                cacheService(model),
                properties,
                objectMapper,
                registry(properties));
    }

    private LooseOpenAiRequestNormalizerRegistry registry(ApiKeyProperties properties) {
        return new LooseOpenAiRequestNormalizerRegistry(Map.of(
                "chatLooseOpenAiRequestNormalizer",
                new ChatLooseOpenAiRequestNormalizerImpl(properties, objectMapper)));
    }

    private static AiModelCacheService cacheService(AiModelCacheEntry model) {
        AiModelCacheSnapshot snapshot = new AiModelCacheSnapshot(1, List.of(model));
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
                throw new UnsupportedOperationException("read-only validation fixture");
            }
        };
    }

    private record LogCapture(
            Logger logger,
            ListAppender<ILoggingEvent> appender) implements AutoCloseable {

        private static LogCapture start() {
            Logger logger = (Logger) LoggerFactory.getLogger(
                    ApiChatRequestValidatorImpl.class);
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

        private void clear() {
            appender.list.clear();
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
