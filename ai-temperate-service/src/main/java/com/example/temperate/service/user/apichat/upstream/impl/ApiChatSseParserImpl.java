package com.example.temperate.service.user.apichat.upstream.impl;

import com.example.temperate.service.user.aiinference.api.ApiInferenceUsage;
import com.example.temperate.service.user.apichat.ApiChatErrorCode;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatProtocolViolation;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatProtocolViolationException;
import com.example.temperate.service.user.apichat.upstream.ApiChatSseParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 该实现是来原样保留 Chat SSE JSON，同时验证 choices/Usage 的最小可信结构并计算取消补偿所需的有界输出字节。
 */
@Service
public final class ApiChatSseParserImpl implements ApiChatSseParser {

    private final ObjectMapper objectMapper;

    public ApiChatSseParserImpl(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public ParsedEvent parse(String data) {
        if ("[DONE]".equals(data)) {
            return new ParsedEvent(
                    List.of(new ParsedChunk(
                            "[DONE]", "[DONE]", null,
                            true, false, 0L, null, false)),
                    Normalization.NONE);
        }
        try {
            JsonNode parsed = objectMapper.readTree(data);
            if (!(parsed instanceof ObjectNode source)) {
                throw protocol(ApiChatProtocolViolation.NON_OBJECT_JSON);
            }
            JsonNode choicesNode = source.get("choices");
            if (!(choicesNode instanceof ArrayNode choices)) {
                throw protocol(ApiChatProtocolViolation.INVALID_CHOICES);
            }
            long outputBytes = 0L;
            String finishReason = null;
            for (JsonNode rawChoice : choices) {
                if (!(rawChoice instanceof ObjectNode choice)) {
                    throw protocol(ApiChatProtocolViolation.INVALID_CHOICES);
                }
                requireNonNegativeInteger(choice.get("index"),
                        ApiChatProtocolViolation.INVALID_CHOICES);
                JsonNode deltaNode = choice.get("delta");
                if (!(deltaNode instanceof ObjectNode delta)) {
                    throw protocol(ApiChatProtocolViolation.INVALID_FIELD_TYPE);
                }
                outputBytes = Math.addExact(outputBytes, visibleDeltaBytes(delta));
                JsonNode finish = choice.get("finish_reason");
                if (finish != null && !finish.isNull()) {
                    if (!finish.isTextual()) {
                        throw protocol(ApiChatProtocolViolation.INVALID_FIELD_TYPE);
                    }
                    finishReason = safeFinishReason(finish.textValue());
                }
            }
            ApiInferenceUsage usage = parseUsage(source.get("usage"));
            String withoutUsage = data;
            if (usage != null) {
                ObjectNode clientVisible = source.deepCopy();
                clientVisible.remove("usage");
                withoutUsage = objectMapper.writeValueAsString(clientVisible);
            }
            return new ParsedEvent(
                    List.of(new ParsedChunk(
                            data,
                            withoutUsage,
                            usage,
                            false,
                            outputBytes > 0L,
                            outputBytes,
                            finishReason,
                            usage != null && choices.isEmpty())),
                    Normalization.NONE);
        } catch (JsonProcessingException failure) {
            throw protocol(ApiChatProtocolViolation.MALFORMED_JSON);
        } catch (ArithmeticException failure) {
            throw protocol(ApiChatProtocolViolation.ARITHMETIC_OVERFLOW);
        }
    }

    private static long visibleDeltaBytes(ObjectNode delta) {
        long bytes = 0L;
        bytes = Math.addExact(bytes, optionalTextBytes(delta.get("content")));
        bytes = Math.addExact(bytes, optionalTextBytes(delta.get("reasoning_content")));
        bytes = Math.addExact(bytes, optionalTextBytes(delta.get("refusal")));
        JsonNode toolCalls = delta.get("tool_calls");
        if (toolCalls != null && !toolCalls.isNull()) {
            if (!(toolCalls instanceof ArrayNode calls)) {
                throw protocol(ApiChatProtocolViolation.INVALID_TOOL_CALLS);
            }
            for (JsonNode rawCall : calls) {
                if (!(rawCall instanceof ObjectNode call)) {
                    throw protocol(ApiChatProtocolViolation.INVALID_TOOL_CALLS);
                }
                JsonNode functionNode = call.get("function");
                if (functionNode != null && !functionNode.isNull()) {
                    if (!(functionNode instanceof ObjectNode function)) {
                        throw protocol(ApiChatProtocolViolation.INVALID_TOOL_CALLS);
                    }
                    bytes = Math.addExact(bytes,
                            optionalTextBytes(function.get("arguments")));
                }
            }
        }
        return bytes;
    }

    private static long optionalTextBytes(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0L;
        }
        if (!node.isTextual()) {
            throw protocol(ApiChatProtocolViolation.INVALID_FIELD_TYPE);
        }
        return node.textValue().getBytes(StandardCharsets.UTF_8).length;
    }

    private static ApiInferenceUsage parseUsage(JsonNode usageNode) {
        if (usageNode == null || usageNode.isNull()) {
            return null;
        }
        if (!(usageNode instanceof ObjectNode usage)) {
            throw protocol(ApiChatProtocolViolation.INVALID_USAGE);
        }
        long prompt = requiredNonNegative(usage, "prompt_tokens");
        long completion = requiredNonNegative(usage, "completion_tokens");
        long cached = optionalDetail(usage.get("prompt_tokens_details"), "cached_tokens");
        long reasoning = optionalDetail(
                usage.get("completion_tokens_details"), "reasoning_tokens");
        JsonNode total = usage.get("total_tokens");
        if (total != null && !total.isNull()) {
            requiredNonNegative(usage, "total_tokens");
        } else {
            Math.addExact(prompt, completion);
        }
        if (cached > prompt || reasoning > completion) {
            throw protocol(ApiChatProtocolViolation.INVALID_USAGE);
        }
        return new ApiInferenceUsage(prompt, completion, cached);
    }

    private static long optionalDetail(JsonNode detailsNode, String field) {
        if (detailsNode == null || detailsNode.isNull()) {
            return 0L;
        }
        if (!(detailsNode instanceof ObjectNode details)) {
            throw protocol(ApiChatProtocolViolation.INVALID_USAGE);
        }
        JsonNode value = details.get(field);
        if (value == null || value.isNull()) {
            return 0L;
        }
        return requiredNonNegative(details, field);
    }

    private static long requiredNonNegative(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 0L) {
            throw protocol(ApiChatProtocolViolation.INVALID_USAGE);
        }
        return value.longValue();
    }

    private static void requireNonNegativeInteger(
            JsonNode value,
            ApiChatProtocolViolation violation) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 0L) {
            throw protocol(violation);
        }
    }

    private static String safeFinishReason(String value) {
        String normalized = value.toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z0-9_]{1,64}") ? normalized : "UNKNOWN";
    }

    private static ApiChatException protocol(ApiChatProtocolViolation violation) {
        return new ApiChatException(
                ApiChatErrorCode.UPSTREAM_PROTOCOL_ERROR,
                "The model upstream returned an invalid streaming protocol.",
                null,
                new ApiChatProtocolViolationException(violation));
    }
}
