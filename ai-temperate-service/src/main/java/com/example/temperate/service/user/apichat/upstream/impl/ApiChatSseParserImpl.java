package com.example.temperate.service.user.apichat.upstream.impl;

import com.example.temperate.service.user.apichat.ApiChatErrorCode;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apichat.billing.ApiChatBillingService.Usage;
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
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 该实现是来对白名单 SSE 字段执行类型校验、重新编码和合并终态拆分，工具 arguments 增量始终保持字符串，任何协议偏差返回 502。
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
                            "[DONE]", null, true, false, 0, null)),
                    Normalization.NONE);
        }
        try {
            JsonNode parsed = objectMapper.readTree(data);
            if (!(parsed instanceof ObjectNode source)) {
                throw protocol(ApiChatProtocolViolation.NON_OBJECT_JSON);
            }
            ObjectNode choicesOutput = objectMapper.createObjectNode();
            copyResponseMetadata(source, choicesOutput);

            long outputBytes = 0;
            String finishReason = null;
            JsonNode choicesNode = source.get("choices");
            ArrayNode choices = choicesOutput.putArray("choices");
            if (choicesNode == null || !choicesNode.isArray() || choicesNode.size() > 1) {
                throw protocol(ApiChatProtocolViolation.INVALID_CHOICES);
            }
            for (JsonNode choiceNode : choicesNode) {
                if (!(choiceNode instanceof ObjectNode choiceSource)) {
                    throw protocol(ApiChatProtocolViolation.INVALID_CHOICES);
                }
                ObjectNode choice = choices.addObject();
                copyIntegral(choiceSource, choice, "index", false);
                if (choice.path("index").longValue() != 0L) {
                    throw protocol(ApiChatProtocolViolation.UNSUPPORTED_CHOICE_INDEX);
                }
                JsonNode deltaNode = choiceSource.get("delta");
                if (!(deltaNode instanceof ObjectNode deltaSource)) {
                    throw protocol(ApiChatProtocolViolation.INVALID_FIELD_TYPE);
                }
                ObjectNode delta = choice.putObject("delta");
                copyText(deltaSource, delta, "role", true);
                JsonNode content = deltaSource.get("content");
                if (content != null && !content.isNull()) {
                    if (!content.isTextual()) {
                        throw protocol(ApiChatProtocolViolation.INVALID_FIELD_TYPE);
                    }
                    delta.put("content", content.textValue());
                    outputBytes = Math.addExact(outputBytes,
                            content.textValue().getBytes(StandardCharsets.UTF_8).length);
                }
                JsonNode reasoningContent = deltaSource.get("reasoning_content");
                if (reasoningContent != null && !reasoningContent.isNull()) {
                    if (!reasoningContent.isTextual()) {
                        throw protocol(ApiChatProtocolViolation.INVALID_FIELD_TYPE);
                    }
                    // 推理片段与普通文本同属可见 SSE 协议字段；只转发白名单内容，不向日志泄露正文。
                    delta.put("reasoning_content", reasoningContent.textValue());
                    outputBytes = Math.addExact(outputBytes,
                            reasoningContent.textValue().getBytes(StandardCharsets.UTF_8).length);
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
                    throw protocol(ApiChatProtocolViolation.INVALID_FIELD_TYPE);
                }
            }
            ParsedUsage parsedUsage = parseUsage(source.get("usage"));
            ParsedChunk choicesChunk = new ParsedChunk(
                    objectMapper.writeValueAsString(choicesOutput),
                    null,
                    false,
                    outputBytes > 0,
                    outputBytes,
                    finishReason);
            if (parsedUsage == null) {
                return new ParsedEvent(List.of(choicesChunk), Normalization.NONE);
            }

            if (choices.isEmpty()) {
                choicesOutput.set("usage", parsedUsage.normalized());
                return new ParsedEvent(
                        List.of(new ParsedChunk(
                                objectMapper.writeValueAsString(choicesOutput),
                                parsedUsage.billingUsage(),
                                false,
                                false,
                                0,
                                null)),
                        Normalization.NONE);
            }

            // 8317 会把最终 choice 与 Usage 合并；边界层必须拆成 OpenAI 标准顺序，避免业务状态机把同一事件误判为重复 Usage。
            ObjectNode usageOutput = objectMapper.createObjectNode();
            copyResponseMetadata(source, usageOutput);
            usageOutput.putArray("choices");
            usageOutput.set("usage", parsedUsage.normalized());
            ParsedChunk usageChunk = new ParsedChunk(
                    objectMapper.writeValueAsString(usageOutput),
                    parsedUsage.billingUsage(),
                    false,
                    false,
                    0,
                    null);
            return new ParsedEvent(
                    List.of(choicesChunk, usageChunk),
                    Normalization.COMBINED_CHOICES_AND_USAGE);
        } catch (JsonProcessingException exception) {
            throw protocol(ApiChatProtocolViolation.MALFORMED_JSON);
        } catch (ArithmeticException exception) {
            throw protocol(ApiChatProtocolViolation.ARITHMETIC_OVERFLOW);
        }
    }

    private long copyToolCallDeltas(JsonNode source, ArrayNode target) {
        if (!source.isArray()) {
            throw protocol(ApiChatProtocolViolation.INVALID_TOOL_CALLS);
        }
        long bytes = 0;
        for (JsonNode raw : source) {
            if (!(raw instanceof ObjectNode callSource)) {
                throw protocol(ApiChatProtocolViolation.INVALID_TOOL_CALLS);
            }
            ObjectNode call = target.addObject();
            copyIntegral(callSource, call, "index", false);
            copyText(callSource, call, "id", true);
            copyText(callSource, call, "type", true);
            JsonNode functionNode = callSource.get("function");
            if (functionNode != null) {
                if (!(functionNode instanceof ObjectNode functionSource)) {
                    throw protocol(ApiChatProtocolViolation.INVALID_TOOL_CALLS);
                }
                ObjectNode function = call.putObject("function");
                copyText(functionSource, function, "name", true);
                JsonNode arguments = functionSource.get("arguments");
                if (arguments != null) {
                    if (!arguments.isTextual()) {
                        throw protocol(ApiChatProtocolViolation.INVALID_TOOL_CALLS);
                    }
                    function.put("arguments", arguments.textValue());
                    bytes = Math.addExact(bytes,
                            arguments.textValue().getBytes(StandardCharsets.UTF_8).length);
                }
            }
        }
        return bytes;
    }

    private ParsedUsage parseUsage(JsonNode usageNode) {
        if (usageNode == null || usageNode.isNull()) {
            return null;
        }
        if (!(usageNode instanceof ObjectNode source)) {
            throw protocol(ApiChatProtocolViolation.INVALID_USAGE);
        }
        long prompt = requiredNonNegative(source, "prompt_tokens");
        long completion = requiredNonNegative(source, "completion_tokens");
        long cached = 0;
        long reasoning = 0;
        JsonNode detailsNode = source.get("prompt_tokens_details");
        ObjectNode usage = objectMapper.createObjectNode();
        usage.put("prompt_tokens", prompt);
        usage.put("completion_tokens", completion);
        JsonNode total = source.get("total_tokens");
        if (total == null || total.isNull()) {
            usage.put("total_tokens", Math.addExact(prompt, completion));
        } else {
            usage.put("total_tokens", requiredNonNegative(source, "total_tokens"));
        }
        if (detailsNode != null && !detailsNode.isNull()) {
            if (!(detailsNode instanceof ObjectNode details)) {
                throw protocol(ApiChatProtocolViolation.INVALID_USAGE);
            }
            cached = requiredNonNegative(details, "cached_tokens");
            usage.putObject("prompt_tokens_details").put("cached_tokens", cached);
        }
        JsonNode completionDetailsNode = source.get("completion_tokens_details");
        if (completionDetailsNode != null && !completionDetailsNode.isNull()) {
            if (!(completionDetailsNode instanceof ObjectNode details)) {
                throw protocol(ApiChatProtocolViolation.INVALID_USAGE);
            }
            reasoning = requiredNonNegative(details, "reasoning_tokens");
            usage.putObject("completion_tokens_details").put("reasoning_tokens", reasoning);
        }
        if (cached > prompt) {
            throw protocol(ApiChatProtocolViolation.INVALID_USAGE);
        }
        if (reasoning > completion) {
            throw protocol(ApiChatProtocolViolation.INVALID_USAGE);
        }
        return new ParsedUsage(new Usage(prompt, completion, cached), usage);
    }

    private static void copyResponseMetadata(
            ObjectNode source,
            ObjectNode target) {
        copyText(source, target, "id", false);
        copyText(source, target, "object", false);
        copyIntegral(source, target, "created", false);
        copyText(source, target, "model", false);
        copyText(source, target, "system_fingerprint", true);
    }

    private static long requiredNonNegative(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 0) {
            throw protocol(ApiChatProtocolViolation.INVALID_USAGE);
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
                throw protocol(ApiChatProtocolViolation.REQUIRED_FIELD_MISSING);
            }
            return;
        }
        if (!value.isTextual()) {
            throw protocol(ApiChatProtocolViolation.INVALID_FIELD_TYPE);
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
                throw protocol(ApiChatProtocolViolation.REQUIRED_FIELD_MISSING);
            }
            return;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw protocol(ApiChatProtocolViolation.INVALID_FIELD_TYPE);
        }
        target.put(field, value.longValue());
    }

    private static ApiChatException protocol(ApiChatProtocolViolation violation) {
        return new ApiChatException(
                ApiChatErrorCode.UPSTREAM_PROTOCOL_ERROR,
                "The model upstream returned an invalid streaming protocol.",
                null,
                new ApiChatProtocolViolationException(violation));
    }

    /** Usage 的计费事实与白名单 JSON 必须由同一次校验产生，禁止二次读取不可信上游节点。 */
    private record ParsedUsage(
            Usage billingUsage,
            ObjectNode normalized) {
    }
}
