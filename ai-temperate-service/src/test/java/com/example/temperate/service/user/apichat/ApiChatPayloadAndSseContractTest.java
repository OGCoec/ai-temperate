package com.example.temperate.service.user.apichat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.user.apichat.billing.ApiChatBillingService.Usage;
import com.example.temperate.service.user.apichat.provider.impl.ApiChatPayloadFactoryImpl;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatProtocolViolation;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatProtocolViolationException;
import com.example.temperate.service.user.apichat.upstream.ApiChatSseParser.Normalization;
import com.example.temperate.service.user.apichat.upstream.ApiChatSseParser.ParsedChunk;
import com.example.temperate.service.user.apichat.upstream.ApiChatSseParser.ParsedEvent;
import com.example.temperate.service.user.apichat.upstream.impl.ApiChatSseParserImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来锁定发往 8317 的 JSON 类型、强制 Usage 选项、工具参数增量和最终 Usage 解析，防止可预防的 422 与计费缺失。
 */
final class ApiChatPayloadAndSseContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void payloadPreservesJsonTypesAndForcesSingleEffectiveTokenLimit() throws Exception {
        ApiChatRequest request = objectMapper.readValue("""
                {
                  "model":"gpt-test",
                  "messages":[{"role":"user","content":"hello"}],
                  "stream":true,
                  "reasoning_effort":"max",
                  "prompt_cache_key":"codex-session-1",
                  "store":false,
                  "service_tier":"priority",
                  "max_tokens":123,
                  "temperature":0.5,
                  "seed":7,
                  "parallel_tool_calls":true
                }
                """, ApiChatRequest.class);
        ValidatedApiChatRequest validated = new ValidatedApiChatRequest(
                request, model(), 123, 17, false);

        JsonNode payload = new ApiChatPayloadFactoryImpl(objectMapper).create(validated);

        assertThat(payload.get("max_completion_tokens").isIntegralNumber()).isTrue();
        assertThat(payload.get("max_completion_tokens").longValue()).isEqualTo(123);
        assertThat(payload.has("max_tokens")).isFalse();
        assertThat(payload.get("temperature").isNumber()).isTrue();
        assertThat(payload.get("seed").isIntegralNumber()).isTrue();
        assertThat(payload.get("parallel_tool_calls").isBoolean()).isTrue();
        assertThat(payload.path("reasoning_effort").textValue()).isEqualTo("max");
        assertThat(payload.path("prompt_cache_key").textValue()).isEqualTo("codex-session-1");
        assertThat(payload.path("store").booleanValue()).isFalse();
        assertThat(payload.path("service_tier").textValue()).isEqualTo("priority");
        assertThat(payload.at("/stream_options/include_usage").booleanValue()).isTrue();
    }

    @Test
    void payloadFlattensTextOnlyContentPartsWithoutChangingAgentFields()
            throws Exception {
        ApiChatRequest request = objectMapper.readValue("""
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
                     "tool_calls":[{"id":"call_1","type":"function",
                     "function":{"name":"weather","arguments":"{}"}}]},
                    {"role":"tool","tool_call_id":"call_1","content":"sunny"}
                  ],
                  "stream":true,
                  "reasoning_effort":"high",
                  "prompt_cache_key":"agent-session-1",
                  "store":false,
                  "service_tier":"auto"
                }
                """, ApiChatRequest.class);

        JsonNode payload = new ApiChatPayloadFactoryImpl(objectMapper).create(
                new ValidatedApiChatRequest(request, model(), 123, 42, false));

        assertThat(payload.at("/messages/0/content").textValue())
                .isEqualTo("system rules");
        assertThat(payload.at("/messages/1/content").textValue())
                .isEqualTo("hello world");
        assertThat(payload.at("/messages/2/content").textValue())
                .isEqualTo("checking weather");
        assertThat(payload.at("/messages/2/reasoning_content").textValue())
                .isEqualTo("use tool");
        assertThat(payload.at("/messages/2/tool_calls/0/id").textValue())
                .isEqualTo("call_1");
        assertThat(payload.at("/messages/3/content").textValue()).isEqualTo("sunny");
        assertThat(payload.path("reasoning_effort").textValue()).isEqualTo("high");
        assertThat(payload.path("prompt_cache_key").textValue())
                .isEqualTo("agent-session-1");
        assertThat(payload.path("store").booleanValue()).isFalse();
        assertThat(payload.path("service_tier").textValue()).isEqualTo("auto");
    }

    @Test
    void payloadPreservesClaudeCodeLongToolDescriptionVerbatim()
            throws Exception {
        String description = "x".repeat(7_125);
        ApiChatRequest request = objectMapper.readValue("""
                {
                  "model":"gpt-test",
                  "messages":[{"role":"user","content":"hello"}],
                  "stream":true,
                  "tools":[{"type":"function","function":{
                    "name":"claude_tool",
                    "description":"%s",
                    "parameters":{"type":"object","properties":{
                      "value":{"type":"string"}}}}}]
                }
                """.formatted(description), ApiChatRequest.class);

        JsonNode payload = new ApiChatPayloadFactoryImpl(objectMapper).create(
                new ValidatedApiChatRequest(request, model(), 123, 42, false));

        assertThat(payload.at("/tools/0/function/description").textValue())
                .isEqualTo(description);
        assertThat(payload.at("/tools/0/function/parameters/properties/value/type")
                .textValue()).isEqualTo("string");
    }

    @Test
    void payloadAndSsePreserveReasoningContentWithoutDoubleBillingReasoningTokens()
            throws Exception {
        ApiChatRequest request = objectMapper.readValue("""
                {"model":"gpt-test","messages":[
                 {"role":"assistant","content":null,"reasoning_content":"inspect tool result",
                  "tool_calls":[{"id":"call_1","type":"function",
                  "function":{"name":"weather","arguments":"{}"}}]}],"stream":true}
                """, ApiChatRequest.class);
        JsonNode payload = new ApiChatPayloadFactoryImpl(objectMapper).create(
                new ValidatedApiChatRequest(request, model(), 123, 17, false));
        ApiChatSseParserImpl parser = new ApiChatSseParserImpl(objectMapper);
        ParsedChunk choice = onlyChunk(parser.parse("""
                {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,
                 "model":"gpt-test","choices":[{"index":0,
                 "delta":{"reasoning_content":"inspect tool result"},"finish_reason":null}]}
                """));
        ParsedChunk usage = onlyChunk(parser.parse("""
                {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,
                 "model":"gpt-test","choices":[],"usage":{"prompt_tokens":20,
                 "completion_tokens":5,"total_tokens":25,
                 "completion_tokens_details":{"reasoning_tokens":3}}}
                """));

        assertThat(payload.at("/messages/0/reasoning_content").textValue())
                .isEqualTo("inspect tool result");
        assertThat(objectMapper.readTree(choice.serializedData())
                .at("/choices/0/delta/reasoning_content").textValue())
                .isEqualTo("inspect tool result");
        assertThat(choice.outputUtf8Bytes()).isPositive();
        assertThat(objectMapper.readTree(usage.serializedData())
                .at("/usage/completion_tokens_details/reasoning_tokens").longValue())
                .isEqualTo(3);
        assertThat(usage.usage()).isEqualTo(new Usage(20, 5, 0));
    }

    @Test
    void parserKeepsToolArgumentsAsIncrementalStringAndReadsCachedUsage() throws Exception {
        ApiChatSseParserImpl parser = new ApiChatSseParserImpl(objectMapper);
        ParsedChunk tool = onlyChunk(parser.parse("""
                {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,
                 "model":"gpt-test","choices":[{"index":0,"delta":{"tool_calls":[
                   {"index":0,"id":"call_1","type":"function","function":{"name":"weather","arguments":"{\\\"city\\\":"}}
                 ]},"finish_reason":null}]}
                """));
        ParsedChunk usage = onlyChunk(parser.parse("""
                {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,
                 "model":"gpt-test","choices":[],"usage":{"prompt_tokens":20,
                 "completion_tokens":5,"total_tokens":25,
                 "prompt_tokens_details":{"cached_tokens":7}}}
                """));

        assertThat(objectMapper.readTree(tool.serializedData())
                .at("/choices/0/delta/tool_calls/0/function/arguments").isTextual()).isTrue();
        assertThat(tool.output()).isTrue();
        assertThat(objectMapper.readTree(usage.serializedData()).path("choices").isEmpty())
                .isTrue();
        assertThat(usage.usage().promptTokens()).isEqualTo(20);
        assertThat(usage.usage().cachedPromptTokens()).isEqualTo(7);
        ParsedEvent done = parser.parse("[DONE]");
        assertThat(done.normalization()).isEqualTo(Normalization.NONE);
        assertThat(done.chunks()).hasSize(1);
        assertThat(done.chunks().get(0).done()).isTrue();
    }

    @Test
    void parserSplitsCombinedChoicesAndUsageIntoCanonicalOrderedFrames()
            throws Exception {
        ApiChatSseParserImpl parser = new ApiChatSseParserImpl(objectMapper);

        ParsedEvent event = parser.parse("""
                {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,
                 "model":"gpt-test","system_fingerprint":"fp-test",
                 "choices":[{"index":0,"delta":{"content":"芝"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":20,"completion_tokens":5,"total_tokens":25,
                 "prompt_tokens_details":{"cached_tokens":7}}}
                """);

        assertThat(event.normalization())
                .isEqualTo(Normalization.COMBINED_CHOICES_AND_USAGE);
        assertThat(event.chunks()).hasSize(2);
        ParsedChunk choices = event.chunks().get(0);
        ParsedChunk usage = event.chunks().get(1);
        JsonNode choicesJson = objectMapper.readTree(choices.serializedData());
        JsonNode usageJson = objectMapper.readTree(usage.serializedData());
        assertThat(choicesJson.has("usage")).isFalse();
        assertThat(choicesJson.at("/choices/0/delta/content").textValue()).isEqualTo("芝");
        assertThat(choices.finishReason()).isEqualTo("STOP");
        assertThat(usageJson.path("choices").isArray()).isTrue();
        assertThat(usageJson.path("choices").isEmpty()).isTrue();
        assertThat(usageJson.at("/usage/prompt_tokens").longValue()).isEqualTo(20);
        assertThat(usage.usage()).isEqualTo(new Usage(20, 5, 7));
        assertThat(usage.output()).isFalse();
    }

    @Test
    void parserSplitsToolDeltaFromCombinedUsageWithoutChangingArguments()
            throws Exception {
        ApiChatSseParserImpl parser = new ApiChatSseParserImpl(objectMapper);

        ParsedEvent event = parser.parse("""
                {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,
                 "model":"gpt-test","choices":[{"index":0,"delta":{"tool_calls":[
                   {"index":0,"id":"call_1","type":"function","function":{"name":"weather","arguments":"{\\\"city\\\":\\\"芝加哥\\\"}"}}
                 ]},"finish_reason":"tool_calls"}],
                 "usage":{"prompt_tokens":20,"completion_tokens":5,"total_tokens":25}}
                """);

        assertThat(event.chunks()).hasSize(2);
        assertThat(objectMapper.readTree(event.chunks().get(0).serializedData())
                .at("/choices/0/delta/tool_calls/0/function/arguments").textValue())
                .isEqualTo("{\"city\":\"芝加哥\"}");
        assertThat(event.chunks().get(1).usage()).isNotNull();
    }

    @Test
    void parserRejectsInvalidUsageAndMultipleChoices() {
        ApiChatSseParserImpl parser = new ApiChatSseParserImpl(objectMapper);

        for (String invalidUsage : List.of(
                "{\"prompt_tokens\":-1,\"completion_tokens\":1,\"total_tokens\":0}",
                "{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":-1}",
                "{\"prompt_tokens\":1.5,\"completion_tokens\":1,\"total_tokens\":2}",
                "{\"prompt_tokens\":1,\"completion_tokens\":1,"
                        + "\"prompt_tokens_details\":{\"cached_tokens\":2}}",
                "{\"prompt_tokens\":1,\"completion_tokens\":1,"
                        + "\"completion_tokens_details\":{\"reasoning_tokens\":2}}")) {
            assertThatThrownBy(() -> parser.parse("""
                    {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,
                     "model":"gpt-test","choices":[],"usage":%s}
                    """.formatted(invalidUsage)))
                    .isInstanceOf(ApiChatException.class);
        }

        assertThatThrownBy(() -> parser.parse("""
                {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,
                 "model":"gpt-test","choices":[
                   {"index":0,"delta":{},"finish_reason":null},
                   {"index":1,"delta":{},"finish_reason":null}]}
                """))
                .isInstanceOf(ApiChatException.class);
    }

    @Test
    void parserRejectsNonStringToolArgumentsAsUpstreamProtocolError() {
        ApiChatSseParserImpl parser = new ApiChatSseParserImpl(objectMapper);

        assertThatThrownBy(() -> parser.parse("""
                {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,
                 "model":"gpt-test","choices":[{"index":0,"delta":{"tool_calls":[
                   {"index":0,"function":{"arguments":{"city":"Paris"}}}
                 ]},"finish_reason":null}]}
                """))
                .isInstanceOf(ApiChatException.class)
                .extracting(failure -> ((ApiChatException) failure).code())
                .isEqualTo(ApiChatErrorCode.UPSTREAM_PROTOCOL_ERROR);
    }

    @Test
    void parserPreservesSafeViolationReasonWithoutUpstreamBody() {
        ApiChatSseParserImpl parser = new ApiChatSseParserImpl(objectMapper);

        assertThatThrownBy(() -> parser.parse("not-json-secret-body"))
                .isInstanceOf(ApiChatException.class)
                .satisfies(failure -> {
                    assertThat(failure.getMessage())
                            .doesNotContain("not-json-secret-body");
                    assertThat(failure.getCause())
                            .isInstanceOf(ApiChatProtocolViolationException.class);
                    assertThat(((ApiChatProtocolViolationException) failure.getCause())
                            .violation())
                            .isEqualTo(ApiChatProtocolViolation.MALFORMED_JSON);
                });
    }

    @Test
    void parsedEventCopiesAndBoundsItsNormalizedChunks() {
        ParsedChunk chunk = new ParsedChunk("[DONE]", null, true, false, 0, null);
        ArrayList<ParsedChunk> mutable = new ArrayList<>(List.of(chunk));

        ParsedEvent event = new ParsedEvent(mutable, Normalization.NONE);
        mutable.clear();

        assertThat(event.chunks()).containsExactly(chunk);
        assertThatThrownBy(() -> event.chunks().add(chunk))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new ParsedEvent(
                List.of(chunk, chunk, chunk),
                Normalization.NONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParsedEvent(
                List.of(chunk, chunk),
                Normalization.NONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AiModelCacheEntry model() {
        return new AiModelCacheEntry(
                1L,
                "gpt-test",
                "openai",
                "test",
                null,
                List.of(),
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                8_192,
                1_024,
                List.of(AiModelCapabilityCode.CHAT_COMPLETIONS));
    }

    private static ParsedChunk onlyChunk(ParsedEvent event) {
        assertThat(event.normalization()).isEqualTo(Normalization.NONE);
        assertThat(event.chunks()).hasSize(1);
        return event.chunks().get(0);
    }
}
