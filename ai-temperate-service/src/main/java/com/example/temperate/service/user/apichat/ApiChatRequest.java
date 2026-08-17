package com.example.temperate.service.user.apichat;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * 该严格 DTO 是来承载 OpenAI Chat Completions 与 Agent 路由所需的白名单字段，并在每一层拒绝未知属性而不是透传给 8317 产生 422。
 */
public record ApiChatRequest(
        String model,
        List<Message> messages,
        JsonNode stream,
        @JsonProperty("stream_options") StreamOptions streamOptions,
        @JsonProperty("max_completion_tokens") JsonNode maxCompletionTokens,
        @JsonProperty("max_tokens") JsonNode maxTokens,
        @JsonProperty("reasoning_effort") JsonNode reasoningEffort,
        @JsonProperty("prompt_cache_key") JsonNode promptCacheKey,
        JsonNode store,
        @JsonProperty("service_tier") JsonNode serviceTier,
        JsonNode temperature,
        @JsonProperty("top_p") JsonNode topP,
        @JsonProperty("presence_penalty") JsonNode presencePenalty,
        @JsonProperty("frequency_penalty") JsonNode frequencyPenalty,
        JsonNode stop,
        JsonNode seed,
        JsonNode n,
        List<Tool> tools,
        @JsonProperty("tool_choice") JsonNode toolChoice,
        @JsonProperty("parallel_tool_calls") JsonNode parallelToolCalls) {

    @JsonAnySetter
    public void rejectUnknown(String name, JsonNode value) {
        throw ApiChatException.invalid("Request contains an unsupported field.", name);
    }

    /** 消息只允许字符串或纯文本 parts content、assistant 推理历史、tool_calls 和 tool_call_id 四类核心字段。 */
    public record Message(
            String role,
            JsonNode content,
            @JsonProperty("reasoning_content") JsonNode reasoningContent,
            @JsonProperty("tool_calls") List<ToolCall> toolCalls,
            @JsonProperty("tool_call_id") String toolCallId) {

        @JsonAnySetter
        public void rejectUnknown(String name, JsonNode value) {
            throw ApiChatException.invalid("Message contains an unsupported field.", "messages");
        }
    }

    /** Assistant 历史工具调用遵循 OpenAI 的 id/type/function 结构。 */
    public record ToolCall(String id, String type, FunctionCall function) {

        @JsonAnySetter
        public void rejectUnknown(String name, JsonNode value) {
            throw ApiChatException.invalid("Tool call contains an unsupported field.", "messages");
        }
    }

    /** function.arguments 必须保持 JSON 字符串，不能在边界解析成任意对象。 */
    public record FunctionCall(String name, String arguments) {

        @JsonAnySetter
        public void rejectUnknown(String name, JsonNode value) {
            throw ApiChatException.invalid("Function call contains an unsupported field.", "messages");
        }
    }

    /** 工具声明第一版只接受 type=function。 */
    public record Tool(String type, FunctionDefinition function) {

        @JsonAnySetter
        public void rejectUnknown(String name, JsonNode value) {
            throw ApiChatException.invalid("Tool contains an unsupported field.", "tools");
        }
    }

    /** parameters 保留有界 JSON Schema 对象，由验证器限制深度、节点数和类型。 */
    public record FunctionDefinition(
            String name,
            String description,
            JsonNode parameters) {

        @JsonAnySetter
        public void rejectUnknown(String name, JsonNode value) {
            throw ApiChatException.invalid("Function definition contains an unsupported field.", "tools");
        }
    }

    /** include_usage 缺省为 false，但后端仍会强制上游返回最终 Usage。 */
    public record StreamOptions(@JsonProperty("include_usage") JsonNode includeUsage) {

        @JsonAnySetter
        public void rejectUnknown(String name, JsonNode value) {
            throw ApiChatException.invalid(
                    "Stream options contain an unsupported field.",
                    "stream_options");
        }
    }
}
