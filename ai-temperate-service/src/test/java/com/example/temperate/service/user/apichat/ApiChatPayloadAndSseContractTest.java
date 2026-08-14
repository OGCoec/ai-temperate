package com.example.temperate.service.user.apichat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.user.apichat.provider.impl.ApiChatPayloadFactoryImpl;
import com.example.temperate.service.user.apichat.upstream.ApiChatSseParser.ParsedChunk;
import com.example.temperate.service.user.apichat.upstream.impl.ApiChatSseParserImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
        assertThat(payload.at("/stream_options/include_usage").booleanValue()).isTrue();
    }

    @Test
    void parserKeepsToolArgumentsAsIncrementalStringAndReadsCachedUsage() throws Exception {
        ApiChatSseParserImpl parser = new ApiChatSseParserImpl(objectMapper);
        ParsedChunk tool = parser.parse("""
                {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,
                 "model":"gpt-test","choices":[{"index":0,"delta":{"tool_calls":[
                   {"index":0,"id":"call_1","type":"function","function":{"name":"weather","arguments":"{\\\"city\\\":"}}
                 ]},"finish_reason":null}]}
                """);
        ParsedChunk usage = parser.parse("""
                {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,
                 "model":"gpt-test","choices":[],"usage":{"prompt_tokens":20,
                 "completion_tokens":5,"total_tokens":25,
                 "prompt_tokens_details":{"cached_tokens":7}}}
                """);

        assertThat(objectMapper.readTree(tool.serializedData())
                .at("/choices/0/delta/tool_calls/0/function/arguments").isTextual()).isTrue();
        assertThat(tool.output()).isTrue();
        assertThat(usage.usageOnly()).isTrue();
        assertThat(usage.usage().promptTokens()).isEqualTo(20);
        assertThat(usage.usage().cachedPromptTokens()).isEqualTo(7);
        assertThat(parser.parse("[DONE]").done()).isTrue();
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
}
