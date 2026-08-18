package com.example.temperate.service.user.apichat;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiinference.api.ApiInferenceUsage;
import com.example.temperate.service.user.apichat.upstream.ApiChatJsonResult;
import com.example.temperate.service.user.apichat.upstream.ApiChatSseParser.ParsedChunk;
import com.example.temperate.service.user.apichat.upstream.impl.ApiChatJsonParserImpl;
import com.example.temperate.service.user.apichat.upstream.impl.ApiChatSseParserImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来约束 Chat 成功响应保持上游 JSON 字段和多 choice，只旁路提取计费事实。
 */
final class OpenAiApiChatResponseParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void streamingParserPreservesMultipleChoicesLogprobsAndExtensions()
            throws Exception {
        String data = """
                {"id":"chatcmpl_1","object":"chat.completion.chunk","created":1,
                 "model":"gpt-test","system_fingerprint":"fp_1","future_field":{"x":1},
                 "choices":[
                   {"index":0,"delta":{"content":"A","refusal":null},
                    "logprobs":{"content":[]},"finish_reason":null},
                   {"index":1,"delta":{"reasoning_content":"B","tool_calls":[{
                     "index":0,"id":"call_1","type":"function","function":{
                     "name":"lookup","arguments":"{}"}}]},"finish_reason":"tool_calls"}
                 ]}
                """;

        ParsedChunk parsed = new ApiChatSseParserImpl(objectMapper)
                .parse(data).chunks().getFirst();

        assertThat(parsed.serializedData()).isEqualTo(data);
        assertThat(objectMapper.readTree(parsed.serializedData()).path("choices"))
                .hasSize(2);
        assertThat(objectMapper.readTree(parsed.serializedData()).path("future_field").path("x")
                .intValue()).isEqualTo(1);
        assertThat(parsed.output()).isTrue();
        assertThat(parsed.finishReason()).isEqualTo("TOOL_CALLS");
    }

    @Test
    void combinedUsageCanBeHiddenWithoutRemovingTheChoice() throws Exception {
        String data = """
                {"id":"chatcmpl_1","object":"chat.completion.chunk","created":1,
                 "model":"gpt-test","choices":[{"index":0,"delta":{"content":"A"},
                 "finish_reason":"stop"}],"usage":{"prompt_tokens":10,
                 "completion_tokens":2,"total_tokens":12,
                 "prompt_tokens_details":{"cached_tokens":3}}}
                """;

        ParsedChunk parsed = new ApiChatSseParserImpl(objectMapper)
                .parse(data).chunks().getFirst();

        assertThat(parsed.usage()).isEqualTo(new ApiInferenceUsage(10, 2, 3));
        assertThat(objectMapper.readTree(parsed.serializedDataWithoutUsage())
                .has("usage")).isFalse();
        assertThat(objectMapper.readTree(parsed.serializedDataWithoutUsage())
                .path("choices")).hasSize(1);
        assertThat(parsed.usageOnly()).isFalse();
    }

    @Test
    void jsonParserReturnsOriginalObjectAndAuthoritativeTotalUsage() throws Exception {
        var response = objectMapper.readTree("""
                {"id":"chatcmpl_1","object":"chat.completion","created":1,
                 "model":"gpt-test","future_field":"kept","choices":[
                  {"index":0,"message":{"role":"assistant","content":"A"},
                   "finish_reason":"stop"},
                  {"index":1,"message":{"role":"assistant","content":"B"},
                   "finish_reason":"length"}],
                 "usage":{"prompt_tokens":20,"completion_tokens":7,"total_tokens":27,
                  "prompt_tokens_details":{"cached_tokens":5}}}
                """);

        ApiChatJsonResult parsed = new ApiChatJsonParserImpl().parse(response);

        assertThat(parsed.response()).isSameAs(response);
        assertThat(parsed.response().path("future_field").textValue()).isEqualTo("kept");
        assertThat(parsed.response().path("choices")).hasSize(2);
        assertThat(parsed.usage()).isEqualTo(new ApiInferenceUsage(20, 7, 5));
        assertThat(parsed.finishReason()).isEqualTo("LENGTH");
    }
}
