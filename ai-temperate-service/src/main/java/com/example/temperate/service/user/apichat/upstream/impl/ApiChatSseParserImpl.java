package com.example.temperate.service.user.apichat.upstream.impl;

import com.example.temperate.service.user.apichat.ApiChatErrorCode;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apichat.billing.ApiChatBillingService.Usage;
import com.example.temperate.service.user.apichat.upstream.ApiChatSseParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 该实现是来对白名单 SSE 字段执行类型校验并重新编码，工具 arguments 增量始终保持字符串，任何协议偏差返回 502。
 */
@Service
public final class ApiChatSseParserImpl implements ApiChatSseParser {

    private final ObjectMapper objectMapper;

    public ApiChatSseParserImpl(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public ParsedChunk parse(String data) {
        if ("[DONE]".equals(data)) {
            return new ParsedChunk("[DONE]", null, true, false, 0, null, false);
        }
        try {
            JsonNode parsed = objectMapper.readTree(data);
            if (!(parsed instanceof ObjectNode source)) {
                throw protocol();
            }
            ObjectNode output = objectMapper.createObjectNode();
            copyText(source, output, "id", false);
            copyText(source, output, "object", false);
            copyIntegral(source, output, "created", false);
            copyText(source, output, "model", false);
            copyText(source, output, "system_fingerprint", true);

            long outputBytes = 0;
            String finishReason = null;
            JsonNode choicesNode = source.get("choices");
            ArrayNode choices = output.putArray("choices");
            if (choicesNode == null || !choicesNode.isArray() || choicesNode.size() > 1) {
                throw protocol();
            }
            for (JsonNode choiceNode : choicesNode) {
                if (!(choiceNode instanceof ObjectNode choiceSource)) {
                    throw protocol();
                }
                ObjectNode choice = choices.addObject();
                copyIntegral(choiceSource, choice, "index", false);
                if (choice.path("index").longValue() != 0L) {
                    throw protocol();
                }
                JsonNode deltaNode = choiceSource.get("delta");
                if (!(deltaNode instanceof ObjectNode deltaSource)) {
                    throw protocol();
                }
                ObjectNode delta = choice.putObject("delta");
                copyText(deltaSource, delta, "role", true);
                JsonNode content = deltaSource.get("content");
                if (content != null && !content.isNull()) {
                    if (!content.isTextual()) {
                        throw protocol();
                    }
                    delta.put("content", content.textValue());
                    outputBytes = Math.addExact(outputBytes,
                            content.textValue().getBytes(StandardCharsets.UTF_8).length);
                }
                JsonNode toolCalls = deltaSource.get("tool_calls");
                if (toolCalls != null) {
                    outputBytes = Math.addExact(outputBytes,
                            copyToolCallDeltas(toolCalls, delta.putArray("tool_calls")));
                }
                JsonNode finish = choiceSource.get("finish_reason");
                if (finish == null || finish.isNull()) {
                    choice.putNull("finish_reason");
                } else if (finish.isTextual()) {
                    choice.put("finish_reason", finish.textValue());
                    finishReason = finish.textValue().toUpperCase(java.util.Locale.ROOT);
                } else {
                    throw protocol();
                }
            }
            Usage usage = parseUsage(source.get("usage"), output);
            boolean usageOnly = usage != null && choices.isEmpty();
            return new ParsedChunk(
                    objectMapper.writeValueAsString(output),
                    usage,
                    false,
                    outputBytes > 0,
                    outputBytes,
                    finishReason,
                    usageOnly);
        } catch (JsonProcessingException | ArithmeticException exception) {
            throw protocol();
        }
    }

    private long copyToolCallDeltas(JsonNode source, ArrayNode target) {
        if (!source.isArray()) {
            throw protocol();
        }
        long bytes = 0;
        for (JsonNode raw : source) {
            if (!(raw instanceof ObjectNode callSource)) {
                throw protocol();
            }
            ObjectNode call = target.addObject();
            copyIntegral(callSource, call, "index", false);
            copyText(callSource, call, "id", true);
            copyText(callSource, call, "type", true);
            JsonNode functionNode = callSource.get("function");
            if (functionNode != null) {
                if (!(functionNode instanceof ObjectNode functionSource)) {
                    throw protocol();
                }
                ObjectNode function = call.putObject("function");
                copyText(functionSource, function, "name", true);
                JsonNode arguments = functionSource.get("arguments");
                if (arguments != null) {
                    if (!arguments.isTextual()) {
                        throw protocol();
                    }
                    function.put("arguments", arguments.textValue());
                    bytes = Math.addExact(bytes,
                            arguments.textValue().getBytes(StandardCharsets.UTF_8).length);
                }
            }
        }
        return bytes;
    }

    private Usage parseUsage(JsonNode usageNode, ObjectNode output) {
        if (usageNode == null || usageNode.isNull()) {
            return null;
        }
        if (!(usageNode instanceof ObjectNode source)) {
            throw protocol();
        }
        long prompt = requiredNonNegative(source, "prompt_tokens");
        long completion = requiredNonNegative(source, "completion_tokens");
        long cached = 0;
        JsonNode detailsNode = source.get("prompt_tokens_details");
        ObjectNode usage = output.putObject("usage");
        usage.put("prompt_tokens", prompt);
        usage.put("completion_tokens", completion);
        JsonNode total = source.get("total_tokens");
        if (total != null && total.isIntegralNumber() && total.canConvertToLong()) {
            usage.put("total_tokens", total.longValue());
        } else {
            usage.put("total_tokens", Math.addExact(prompt, completion));
        }
        if (detailsNode != null && !detailsNode.isNull()) {
            if (!(detailsNode instanceof ObjectNode details)) {
                throw protocol();
            }
            cached = requiredNonNegative(details, "cached_tokens");
            usage.putObject("prompt_tokens_details").put("cached_tokens", cached);
        }
        if (cached > prompt) {
            throw protocol();
        }
        return new Usage(prompt, completion, cached);
    }

    private static long requiredNonNegative(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 0) {
            throw protocol();
        }
        return value.longValue();
    }

    private static void copyText(
            ObjectNode source,
            ObjectNode target,
            String field,
            boolean optional) {
        JsonNode value = source.get(field);
        if (value == null || value.isNull()) {
            if (!optional) {
                throw protocol();
            }
            return;
        }
        if (!value.isTextual()) {
            throw protocol();
        }
        target.put(field, value.textValue());
    }

    private static void copyIntegral(
            ObjectNode source,
            ObjectNode target,
            String field,
            boolean optional) {
        JsonNode value = source.get(field);
        if (value == null || value.isNull()) {
            if (!optional) {
                throw protocol();
            }
            return;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw protocol();
        }
        target.put(field, value.longValue());
    }

    private static ApiChatException protocol() {
        return new ApiChatException(
                ApiChatErrorCode.UPSTREAM_PROTOCOL_ERROR,
                "The model upstream returned an invalid streaming protocol.",
                null);
    }
}
