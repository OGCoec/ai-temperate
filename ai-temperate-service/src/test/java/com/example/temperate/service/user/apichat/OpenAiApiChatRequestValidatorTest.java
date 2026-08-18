package com.example.temperate.service.user.apichat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.apichat.openai.OpenAiApiChatRequestValidation;
import com.example.temperate.service.user.apichat.openai.impl.OpenAiApiChatRequestValidatorImpl;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来约束 OpenAI Chat Completions 增强路径的原始 JSON 白名单、无状态边界和精确错误字段。
 */
final class OpenAiApiChatRequestValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAiApiChatRequestValidatorImpl validator =
            new OpenAiApiChatRequestValidatorImpl(
                    objectMapper, new ApiKeyProperties());

    @Test
    void preservesCommonTextFunctionAndStructuredOutputFields() throws Exception {
        ObjectNode request = objectMapper.readValue("""
                {
                  "model":"gpt-test",
                  "messages":[
                    {"role":"developer","name":"policy","content":[
                      {"type":"text","text":"Answer as JSON.",
                       "prompt_cache_breakpoint":{"mode":"explicit"}}]},
                    {"role":"assistant","content":null,
                     "function_call":{"name":"weather","arguments":"{}"}},
                    {"role":"function","name":"weather","content":"sunny"}
                  ],
                  "stream":false,
                  "max_completion_tokens":256,
                  "logprobs":true,
                  "top_logprobs":5,
                  "prediction":{"type":"content","content":"{\\\"weather\\\":"},
                  "verbosity":"low",
                  "safety_identifier":"tenant-user-7",
                  "prompt_cache_options":{"ttl":"24h"},
                  "tools":[{"type":"function","function":{
                    "name":"weather","description":"Get weather",
                    "parameters":{"type":"object"},"strict":true}}],
                  "tool_choice":{"type":"function","function":{"name":"weather"}},
                  "response_format":{"type":"json_schema","json_schema":{
                    "name":"weather_result","strict":true,
                    "schema":{"type":"object","additionalProperties":false}}}
                }
                """, ObjectNode.class);

        OpenAiApiChatRequestValidation validated = validator.validate(request);

        assertThat(validated.model()).isEqualTo("gpt-test");
        assertThat(validated.stream()).isFalse();
        assertThat(validated.requestedMaxOutputTokens()).isEqualTo(256L);
        assertThat(validated.functionTools()).isTrue();
        assertThat(validated.structuredOutput()).isTrue();
        assertThat(validated.payload()).isEqualTo(request);
    }

    @Test
    void acceptsLegacyFunctionsAndUsageVisibilityWithoutChangingThePayload()
            throws Exception {
        ObjectNode request = objectMapper.readValue("""
                {
                  "model":"gpt-test",
                  "messages":[{"role":"user","content":"hello"}],
                  "stream":true,
                  "stream_options":{"include_usage":true},
                  "functions":[{"name":"lookup","parameters":{"type":"object"}}],
                  "function_call":{"name":"lookup"}
                }
                """, ObjectNode.class);

        OpenAiApiChatRequestValidation validated = validator.validate(request);

        assertThat(validated.stream()).isTrue();
        assertThat(validated.includeUsage()).isTrue();
        assertThat(validated.functionTools()).isTrue();
        assertThat(validated.payload()).isEqualTo(request);
    }

    @Test
    void rejectsUnknownStatefulAndMultimodalFieldsWithExactParameter() throws Exception {
        assertUnsupported("""
                {"model":"gpt-test","messages":[{"role":"user","content":"hello"}],
                 "stream":false,"unknown_option":true}
                """, "unknown_option");
        assertUnsupported("""
                {"model":"gpt-test","messages":[{"role":"user","content":"hello"}],
                 "store":true}
                """, "store");
        assertUnsupported("""
                {"model":"gpt-test","messages":[{"role":"user","content":[
                 {"type":"image_url","image_url":{"url":"https://example.invalid/x"}}]}]}
                """, "messages[0].content[0].type");
    }

    @Test
    void rejectsCompetingTokenLimitsAndHostedTools() throws Exception {
        assertInvalid("""
                {"model":"gpt-test","messages":[{"role":"user","content":"hello"}],
                 "max_tokens":64,"max_completion_tokens":64}
                """, "max_completion_tokens");
        assertUnsupported("""
                {"model":"gpt-test","messages":[{"role":"user","content":"hello"}],
                 "tools":[{"type":"web_search"}]}
                """, "tools[0].type");
    }

    private void assertUnsupported(String json, String parameter) throws Exception {
        assertFailure(json, parameter, ApiChatErrorCode.UNSUPPORTED_PARAMETER);
    }

    private void assertInvalid(String json, String parameter) throws Exception {
        assertFailure(json, parameter, ApiChatErrorCode.INVALID_REQUEST);
    }

    private void assertFailure(
            String json,
            String parameter,
            ApiChatErrorCode errorCode) throws Exception {
        ObjectNode request = objectMapper.readValue(json, ObjectNode.class);
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(ApiChatException.class)
                .satisfies(failure -> {
                    ApiChatException exception = (ApiChatException) failure;
                    assertThat(exception.code()).isEqualTo(errorCode);
                    assertThat(exception.parameter()).isEqualTo(parameter);
                });
    }
}
