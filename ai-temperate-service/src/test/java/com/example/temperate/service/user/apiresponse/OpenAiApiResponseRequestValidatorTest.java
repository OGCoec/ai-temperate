package com.example.temperate.service.user.apiresponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.apichat.ApiChatErrorCode;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.example.temperate.service.user.apiresponse.openai.OpenAiApiResponseRequestValidation;
import com.example.temperate.service.user.apiresponse.openai.impl.OpenAiApiResponseRequestValidatorImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来约束 OpenAI Responses 增强路径只接受无状态文本、函数工具和结构化输出，并保留原始字段。
 */
final class OpenAiApiResponseRequestValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAiApiResponseRequestValidatorImpl validator =
            new OpenAiApiResponseRequestValidatorImpl(
                    objectMapper, new ApiKeyProperties());

    @Test
    void preservesCommonTextFunctionReasoningAndJsonSchemaFields() throws Exception {
        ObjectNode request = objectMapper.readValue("""
                {
                  "model":"gpt-test",
                  "input":[
                    {"role":"developer","content":[{"type":"input_text","text":"JSON"}]},
                    {"type":"reasoning","summary":[{"type":"summary_text","text":"plan"}]},
                    {"type":"function_call","call_id":"call_1","name":"lookup",
                     "arguments":"{}"},
                    {"type":"function_call_output","call_id":"call_1","output":"ok"}
                  ],
                  "stream":true,
                  "max_output_tokens":256,
                  "reasoning":{"effort":"high","summary":"auto"},
                  "temperature":0.2,
                  "top_p":0.9,
                  "top_logprobs":3,
                  "truncation":"auto",
                  "safety_identifier":"tenant-user-7",
                  "prompt_cache_retention":"24h",
                  "max_tool_calls":4,
                  "tools":[{"type":"function","name":"lookup",
                    "parameters":{"type":"object"},"strict":true}],
                  "tool_choice":{"type":"function","name":"lookup"},
                  "text":{"verbosity":"low","format":{"type":"json_schema",
                    "name":"result","strict":true,
                    "schema":{"type":"object","additionalProperties":false}}},
                  "include":["reasoning.encrypted_content"]
                }
                """, ObjectNode.class);

        OpenAiApiResponseRequestValidation validated = validator.validate(request);

        assertThat(validated.model()).isEqualTo("gpt-test");
        assertThat(validated.stream()).isTrue();
        assertThat(validated.requestedMaxOutputTokens()).isEqualTo(256L);
        assertThat(validated.functionTools()).isTrue();
        assertThat(validated.structuredOutput()).isTrue();
        assertThat(validated.payload().path("store").booleanValue()).isFalse();
        assertThat(validated.payload().path("top_logprobs").intValue()).isEqualTo(3);
        assertThat(validated.payload().at("/text/format/type").textValue())
                .isEqualTo("json_schema");
    }

    @Test
    void normalizesOmittedStoreToFalseWithoutDroppingOtherFields() throws Exception {
        ObjectNode request = objectMapper.readValue("""
                {"model":"gpt-test","input":"hello","service_tier":"auto"}
                """, ObjectNode.class);

        OpenAiApiResponseRequestValidation validated = validator.validate(request);

        assertThat(validated.payload().path("store").booleanValue()).isFalse();
        assertThat(validated.payload().path("service_tier").textValue()).isEqualTo("auto");
        assertThat(validated.stream()).isFalse();
    }

    @Test
    void rejectsStateMultimodalHostedToolsAndUnknownFieldsPrecisely() throws Exception {
        assertUnsupported("""
                {"model":"gpt-test","input":"hello","store":true}
                """, "store");
        assertUnsupported("""
                {"model":"gpt-test","input":"hello","background":true}
                """, "background");
        assertUnsupported("""
                {"model":"gpt-test","input":"hello","previous_response_id":"resp_1"}
                """, "previous_response_id");
        assertUnsupported("""
                {"model":"gpt-test","input":[{"role":"user","content":[
                 {"type":"input_image","image_url":"https://example.invalid/x"}]}]}
                """, "input[0].content[0].type");
        assertUnsupported("""
                {"model":"gpt-test","input":"hello","tools":[{"type":"web_search"}]}
                """, "tools[0].type");
        assertUnsupported("""
                {"model":"gpt-test","input":"hello","future_field":true}
                """, "future_field");
    }

    private void assertUnsupported(String json, String parameter) throws Exception {
        ObjectNode request = objectMapper.readValue(json, ObjectNode.class);
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(ApiChatException.class)
                .satisfies(failure -> {
                    ApiChatException exception = (ApiChatException) failure;
                    assertThat(exception.code())
                            .isEqualTo(ApiChatErrorCode.UNSUPPORTED_PARAMETER);
                    assertThat(exception.parameter()).isEqualTo(parameter);
                });
    }
}
