package com.example.temperate.service.user.apiresponse;

import com.example.temperate.service.user.apichat.ApiChatException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * 该严格 DTO 是来承载 Codex 核心 Responses 白名单字段，并在反序列化边界拒绝未知顶层和嵌套配置字段。
 */
public record ApiResponseRequest(
        JsonNode model,
        JsonNode input,
        JsonNode instructions,
        JsonNode stream,
        JsonNode store,
        @JsonProperty("max_output_tokens") JsonNode maxOutputTokens,
        Reasoning reasoning,
        List<Tool> tools,
        @JsonProperty("tool_choice") JsonNode toolChoice,
        @JsonProperty("parallel_tool_calls") JsonNode parallelToolCalls,
        List<JsonNode> include,
        @JsonProperty("prompt_cache_key") JsonNode promptCacheKey,
        @JsonProperty("service_tier") JsonNode serviceTier,
        Text text,
        JsonNode temperature,
        @JsonProperty("top_p") JsonNode topP) {

    @JsonAnySetter
    public void rejectUnknown(String name, JsonNode value) {
        throw ApiChatException.invalid("Request contains an unsupported field.", name);
    }

    /** 该配置只允许公开 Responses 的推理强度和摘要方式，不接受供应商私有字段。 */
    public record Reasoning(JsonNode effort, JsonNode summary) {

        @JsonAnySetter
        public void rejectUnknown(String name, JsonNode value) {
            throw ApiChatException.invalid(
                    "reasoning contains an unsupported field.", "reasoning");
        }
    }

    /** 该工具声明直接使用 Responses 的扁平 function 结构并保留有界 JSON Schema。 */
    public record Tool(
            JsonNode type,
            JsonNode name,
            JsonNode description,
            JsonNode parameters,
            JsonNode strict) {

        @JsonAnySetter
        public void rejectUnknown(String name, JsonNode value) {
            throw ApiChatException.invalid("Tool contains an unsupported field.", "tools");
        }
    }

    /** 该文本配置只开放纯文本格式和 verbosity，不允许 JSON Schema 等结构化输出。 */
    public record Text(Format format, JsonNode verbosity) {

        @JsonAnySetter
        public void rejectUnknown(String name, JsonNode value) {
            throw ApiChatException.invalid("text contains an unsupported field.", "text");
        }
    }

    /** 该格式声明固定为 type=text，任何额外结构化输出配置都会在边界被拒绝。 */
    public record Format(JsonNode type) {

        @JsonAnySetter
        public void rejectUnknown(String name, JsonNode value) {
            throw ApiChatException.invalid(
                    "text.format contains an unsupported field.", "text.format");
        }
    }
}
