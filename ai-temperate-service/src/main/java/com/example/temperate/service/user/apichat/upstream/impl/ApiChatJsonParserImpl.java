package com.example.temperate.service.user.apichat.upstream.impl;

import com.example.temperate.service.user.aiinference.api.ApiInferenceUsage;
import com.example.temperate.service.user.apichat.ApiChatErrorCode;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apichat.upstream.ApiChatJsonParser;
import com.example.temperate.service.user.apichat.upstream.ApiChatJsonResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * 该实现是来验证 Chat 非流式最小结构和总 Usage，不重建 choices、logprobs、工具调用或供应商扩展字段。
 */
@Service
public final class ApiChatJsonParserImpl implements ApiChatJsonParser {

    @Override
    public ApiChatJsonResult parse(JsonNode response) {
        if (!(response instanceof ObjectNode object)) {
            throw protocol("The model upstream returned a non-object Chat response.");
        }
        JsonNode choicesNode = object.get("choices");
        if (!(choicesNode instanceof ArrayNode choices) || choices.isEmpty()) {
            throw protocol("The model upstream returned invalid Chat choices.");
        }
        String finishReason = "UNKNOWN";
        for (JsonNode rawChoice : choices) {
            if (!(rawChoice instanceof ObjectNode choice)
                    || !nonNegativeInteger(choice.get("index"))
                    || !(choice.get("message") instanceof ObjectNode)) {
                throw protocol("The model upstream returned invalid Chat choices.");
            }
            JsonNode finish = choice.get("finish_reason");
            if (finish != null && !finish.isNull()) {
                if (!finish.isTextual()) {
                    throw protocol("The model upstream returned an invalid finish_reason.");
                }
                finishReason = safeFinishReason(finish.textValue());
            }
        }
        ApiInferenceUsage usage = parseUsage(object.get("usage"));
        return new ApiChatJsonResult(response, usage, finishReason);
    }

    private static ApiInferenceUsage parseUsage(JsonNode usageNode) {
        if (!(usageNode instanceof ObjectNode usage)) {
            throw protocol("The model upstream omitted Chat Usage.");
        }
        long prompt = requiredNonNegative(usage, "prompt_tokens");
        long completion = requiredNonNegative(usage, "completion_tokens");
        long cached = optionalDetail(usage.get("prompt_tokens_details"), "cached_tokens");
        long reasoning = optionalDetail(
                usage.get("completion_tokens_details"), "reasoning_tokens");
        JsonNode total = usage.get("total_tokens");
        if (total != null && !total.isNull()) {
            requiredNonNegative(usage, "total_tokens");
        }
        if (cached > prompt || reasoning > completion) {
            throw protocol("The model upstream returned inconsistent Chat Usage.");
        }
        return new ApiInferenceUsage(prompt, completion, cached);
    }

    private static long optionalDetail(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return 0L;
        }
        if (!(node instanceof ObjectNode details)) {
            throw protocol("The model upstream returned invalid Chat Usage details.");
        }
        JsonNode value = details.get(field);
        return value == null || value.isNull() ? 0L : requiredNonNegative(details, field);
    }

    private static long requiredNonNegative(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (!nonNegativeInteger(value)) {
            throw protocol("The model upstream returned invalid Chat Usage.");
        }
        return value.longValue();
    }

    private static boolean nonNegativeInteger(JsonNode value) {
        return value != null && value.isIntegralNumber()
                && value.canConvertToLong() && value.longValue() >= 0L;
    }

    private static String safeFinishReason(String value) {
        String normalized = value.toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z0-9_]{1,64}") ? normalized : "UNKNOWN";
    }

    private static ApiChatException protocol(String message) {
        return new ApiChatException(
                ApiChatErrorCode.UPSTREAM_PROTOCOL_ERROR, message, null);
    }
}
